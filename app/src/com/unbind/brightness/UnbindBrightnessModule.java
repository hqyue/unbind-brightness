package com.unbind.brightness;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 亮度解绑 v1.0 —— 修复 HyperOS 4 下第三方视频 App 调亮后退出，系统亮度/滑块不回落的问题。
 *
 * <p><b>根因（v3↔v4 真机 smali diff）：</b>澎湃4 比澎湃3 多出整条“前台应用亮度同步”管道，
 * 把视频亮度写回了系统亮度基值：
 * <pre>
 *   requestBrightnessSync(F)            // 置 mPendingBrightnessSync（p4 新增，p3 无）
 *     → consumePendingBrightnessSync()  // true
 *     → syncBrightnessToSettingAndUi()
 *         → updateScreenBrightnessSetting(FFF)  // 改内存系统亮度基值
 *         → saveBrightnessIfNeeded()           // 落盘 SCREEN_BRIGHTNESS（=绑定到系统亮度）
 * </pre>
 * 退出视频后系统亮度已是视频值 → 不回落。
 *
 * <p><b>修复（=还原澎湃3）：</b>no-op 掉 requestBrightnessSync，源头掐断写回管道，
 * 视频亮度只作用于显示层，永不写回系统亮度/滑块。全应用通用，不依赖包名。</p>
 *
 * <p><b>回落过渡：</b>退出后窗口内把 animateScreenBrightness 的 rate 强制为固定时长慢速
 * （rate=|目标-当前|/2.5s，覆盖系统 Fast 斜率，消除断崖跳变），手动/自动共用此单一 hook 点。</p>
 *
 * <p><b>自动模式安全网：</b>额外还原 mScreenAutoBrightness + 桥接 + neutralize p4 私有 override 调用，
 * 覆盖“退出后自动重新收敛到视频值”的自动专属症状。</p>
 */
public class UnbindBrightnessModule extends XposedModule {

    private static final String TAG = "亮度解绑";
    private static XposedInterface sXp;
    private static UnbindBrightnessModule sSelf;

    // 每个 DPC 实例一份“是否在视频 override 中”（避免多显示串扰）
    private static final Map<Object, Boolean> sVideo = new ConcurrentHashMap<>();
    // 自动模式：进视频前保存的自动亮度基线
    private static final Map<Object, Float> sAutoSaved = new ConcurrentHashMap<>();

    // 自动模式：桥接（兜底，保护传感器暂停空窗）
    private static volatile Float sBridgeValue = null;
    private static volatile boolean sBridge = false;

    // 渐变回落：退出视频后的窗口内，把回落强制成“固定时长的慢速动画”
    private static volatile long sGradualUntil = 0;
    private static final long GRADUAL_WINDOW_MS = 6000L;
    private static final float GRADUAL_DURATION_SEC = 2.5f; // 期望回落总时长（秒）
    private static final float GRADUAL_DELTA_THRESHOLD = 0.04f; // 仅对大跨度回落生效，避免拖慢自动模式小幅微调
    // 读当前显示亮度用的字段缓存：mScreenBrightnessRampAnimator.mFirst.mCurrentValue
    private static volatile Field fAnim, fFirst, fCur;

    // 已装载的 neutralize 钩（一次性）
    private static volatile boolean sNeutralizeHooked = false;

    // 缓存的反射句柄（system_server classloader 稳定，解析一次即可）
    private static volatile Class<?> sDbcCls, sSelCls;
    private static volatile Field fSel;
    private static volatile Method mGetAutoStrat;

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        sXp = this;
        sSelf = this;
        ClassLoader cl = param.getClassLoader();
        try {
            sDbcCls = Class.forName(
                    "com.android.server.display.brightness.DisplayBrightnessController", false, cl);
            sSelCls = Class.forName(
                    "com.android.server.display.brightness.DisplayBrightnessStrategySelector", false, cl);
            mGetAutoStrat = sSelCls.getDeclaredMethod("getAutomaticBrightnessStrategy");
            mGetAutoStrat.setAccessible(true);
            fSel = findField(sDbcCls, "mDisplayBrightnessStrategySelector");
        } catch (Throwable t) {
            log("反射初始化失败: " + t);
        }

        Class<?> dpc = null;
        try {
            dpc = Class.forName("com.android.server.display.DisplayPowerController", false, cl);
        } catch (Throwable t) {
            log("DPC 类定位失败: " + t);
        }

        // 钩1：观察视频 override 进入/退出（记录基线 + 启动保持/渐变）
        try {
            Method m1 = findByNameArity(dpc, "setBrightnessOverrideRequest", 1);
            if (m1 == null) throw new NoSuchMethodException("setBrightnessOverrideRequest/1");
            hookIt(m1, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object dpcObj = chain.getThisObject();
                        Object req = (chain.getArgs() != null && !chain.getArgs().isEmpty())
                                ? chain.getArgs().get(0) : null;
                        onOverride(dpcObj, req);
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                }
            });
        } catch (Throwable t) { log("钩1 失败: " + t); }

        // 核心钩（源头掐断“前台应用亮度同步”管道）：
        //   requestBrightnessSync(F) 只在 p4 新增管道内被调用、置 mPendingBrightnessSync；
        //   no-op 它 → consumePendingBrightnessSync() 恒 false → syncBrightnessToSettingAndUi
        //   永不执行 → 视频亮度不写回内存基值 / 系统设置 / 滑块（=还原澎湃3）。
        try {
            Class<?> implCls = Class.forName(
                    "com.android.server.display.DisplayPowerControllerImpl", false, cl);
            Method mSync = implCls.getDeclaredMethod("requestBrightnessSync", float.class);
            mSync.setAccessible(true);
            hook(mSync)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) {
                            return null; // 不 proceed → 源头断管
                        }
                    });
        } catch (Throwable t) { log("核心钩失败: " + t); }

        // 钩4（兜底）：NaN 桥接（传感器暂停的空窗内，用进视频前基线顶住）
        try {
            Method m4 = findByNameArity(sAbc(), "getAutomaticScreenBrightness", 1);
            if (m4 == null) throw new NoSuchMethodException("getAutomaticScreenBrightness/1");
            hookIt(m4, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    try {
                        Float v = (r instanceof Float) ? (Float) r : Float.valueOf(Float.NaN);
                        if (sBridge) {
                            if (Float.isNaN(v.floatValue())) {
                                return (sBridgeValue != null) ? sBridgeValue : v;
                            } else {
                                sBridge = false;
                                return v;
                            }
                        }
                    } catch (Throwable ignored) {}
                    return r;
                }
            });
        } catch (Throwable t) { log("钩4 失败: " + t); }

        // 钩5（渐变回落）：退出视频后的窗口内，把回落强制成固定时长的慢速动画。
        // 做法：rate(亮度单位/秒) = |目标 - 当前显示亮度| / 期望时长，
        // 直接给 RampAnimator 一个“慢”速率，覆盖系统默认的 Fast 斜率（=断崖）。
        // 手动/自动共用此单一 hook 点；当前亮度从 mScreenBrightnessRampAnimator.mFirst.mCurrentValue 读取。
        try {
            Method m5 = findByTypes(dpc, "animateScreenBrightness",
                    float.class, float.class, float.class, boolean.class);
            if (m5 == null) throw new NoSuchMethodException("animateScreenBrightness/FFFZ");
            hookIt(m5, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        if (System.currentTimeMillis() < sGradualUntil) {
                            float target = ((Float) chain.getArgs().get(0)).floatValue();
                            float cur = readCurrentBrightness(chain.getThisObject());
                            float rate;
                            if (!Float.isNaN(cur) && Math.abs(target - cur) > GRADUAL_DELTA_THRESHOLD) {
                                // 大跨度回落：强制固定时长（被系统 maxTime 天花板封顶时也已是系统允许的最慢）
                                rate = Math.abs(target - cur) / GRADUAL_DURATION_SEC;
                            } else {
                                // 小幅变化或读不到当前值：退化用系统慢斜率，不拖慢自动模式正常微调
                                float slow = getF(chain.getThisObject(), "mBrightnessRampRateSlowIncrease");
                                rate = Float.isNaN(slow) ? 0.1f : slow;
                            }
                            if (rate > 0f && !Float.isNaN(rate)) {
                                chain.getArgs().set(2, Float.valueOf(rate));
                            }
                        }
                    } catch (Throwable ignored) {}
                    return chain.proceed();
                }
            });
        } catch (Throwable t) { log("钩5 失败: " + t); }

        log("亮度解绑 v1.0 已装载：单点掐断前台同步管道 + 固定时长慢速回落 + 自动安全网");
    }

    // ==================== neutralize：让 v4 自动亮度退化成 v3 ====================
    private void ensureNeutralizeHooks(Object abc) {
        if (sNeutralizeHooked || abc == null) return;
        try {
            Object impl = getO(abc, "mImpl");
            if (impl == null) {
                for (Field f : abc.getClass().getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(abc);
                        if (v == null) continue;
                        for (Method mm : v.getClass().getDeclaredMethods()) {
                            if ("getOverrideLimitBrightness".equals(mm.getName())) { impl = v; break; }
                        }
                    } catch (Throwable ignored) {}
                    if (impl != null) break;
                }
            }
            if (impl == null) return;
            final Class<?> implCls = impl.getClass();

            // getOverrideLimitBrightness(F Z F)F → 永远返回 p0（真实值）
            hookNeutralize(implCls, "getOverrideLimitBrightness",
                    new Class[]{float.class, boolean.class, float.class}, float.class,
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            return (Float) chain.getArgs().get(0);
                        }
                    });
            // getCustomBrightnessForRefactorPolicy(F F Z)F → 返回 p0（跳过 updateLogicalNit）
            hookNeutralize(implCls, "getCustomBrightnessForRefactorPolicy",
                    new Class[]{float.class, float.class, boolean.class}, float.class,
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            return (Float) chain.getArgs().get(0);
                        }
                    });
            // setMinLogicalNit()V → no-op（v3 无此方法）
            hookNeutralize(implCls, "setMinLogicalNit", new Class[0], void.class,
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            return null;
                        }
                    });
            // updateBrightnessStateForIPA(F F F)V → no-op（v3 无此方法）
            hookNeutralize(implCls, "updateBrightnessStateForIPA",
                    new Class[]{float.class, float.class, float.class}, void.class,
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            return null;
                        }
                    });

            sNeutralizeHooked = true;
        } catch (Throwable t) {
            log("neutralize 装载失败: " + t);
        }
    }

    private void hookNeutralize(Class<?> cls, String name, Class<?>[] types, Class<?> ret,
                                XposedInterface.Hooker h) {
        Method m = findExact(cls, name, types, ret);
        if (m == null) return;
        try { m.setAccessible(true); this.hook(m).intercept(h); } catch (Throwable ignored) {}
    }

    // ============ 视频 override 进入/退出 ============
    private static void onOverride(Object dpc, Object req) {
        float b = (req != null) ? getF(req, "brightness") : Float.NaN;
        boolean entering = (req != null) && !Float.isNaN(b) && b > 0.0005f && b <= 1.0f;
        Boolean prev = sVideo.get(dpc);
        if (prev == null) prev = false;

        if (!prev && entering) {
            sVideo.put(dpc, true);
            sBridge = false;
            if (isAutoMode()) {
                Object abc = getAbc(getO(dpc, "mDisplayBrightnessController"));
                if (abc != null) {
                    sAutoSaved.put(dpc, Float.valueOf(getF(abc, "mScreenAutoBrightness")));
                    if (sSelf != null) sSelf.ensureNeutralizeHooks(abc);
                }
            }
            log("【进入视频】亮度=" + b + " 模式=" + (isAutoMode() ? "自动" : "手动")
                    + " 屏幕已变亮，系统亮度/滑块保持真实值");
        } else if (prev && !entering) {
            sVideo.put(dpc, false);
            boolean auto = isAutoMode();
            sGradualUntil = System.currentTimeMillis() + GRADUAL_WINDOW_MS;
            log("【退出视频】模式=" + (auto ? "自动" : "手动") + " 已启用慢速回落");
            if (auto) restoreAutoState(dpc, getO(dpc, "mDisplayBrightnessController"));
        }
    }

    // ============ 自动模式：还原 mScreenAutoBrightness + 装载 neutralize 钩 ============
    private static void restoreAutoState(Object dpc, Object dbc) {
        if (dbc == null) return;
        Float saved = sAutoSaved.remove(dpc);
        Object abc = getAbc(dbc);
        if (abc == null) return;
        if (sSelf != null) sSelf.ensureNeutralizeHooks(abc);
        if (saved != null && !Float.isNaN(saved.floatValue())) {
            setF(abc, "mScreenAutoBrightness", saved.floatValue());
            sBridgeValue = saved;
            sBridge = true;
        }
        zeroAutoRatio(dbc);
    }

    private static Object getAbc(Object dbc) {
        if (dbc == null) return null;
        Object abc = getO(dbc, "mAutomaticBrightnessController");
        if (abc != null) return abc;
        try {
            Object sel = fSel.get(dbc);
            if (sel != null && mGetAutoStrat != null) {
                Object st = mGetAutoStrat.invoke(sel);
                if (st != null) return getO(st, "mAutomaticBrightnessController");
            }
        } catch (Throwable ignored) {}
        return null;
    }
    private static void zeroAutoRatio(Object dbc) {
        try {
            Object sel = fSel.get(dbc);
            if (sel != null && mGetAutoStrat != null) {
                Object st = mGetAutoStrat.invoke(sel);
                if (st != null) setF(st, "mTemporaryAutoBrightnessAdjRatio", 0f);
            }
        } catch (Throwable ignored) {}
    }
    private static Class<?> sAbc() {
        try {
            return Class.forName("com.android.server.display.AutomaticBrightnessController");
        } catch (Throwable t) { return null; }
    }

    // ============ 稳健装载：按方法名 + 参数形状定位 ============
    private void hookIt(Method m, XposedInterface.Hooker h) {
        try { this.hook(m).intercept(h); }
        catch (Throwable t) { log("挂钩失败: " + t); }
    }
    private static Method findByNameArity(Class<?> cls, String name, int arity) {
        if (cls == null) return null;
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == arity) return m;
        }
        return null;
    }
    private static Method findByTypes(Class<?> cls, String name, Class<?>... types) {
        if (cls == null) return null;
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != types.length) continue;
            boolean ok = true;
            for (int i = 0; i < types.length; i++) { if (ps[i] != types[i]) { ok = false; break; } }
            if (ok) return m;
        }
        return null;
    }
    private static Method findExact(Class<?> cls, String name, Class<?>[] types, Class<?> ret) {
        if (cls == null) return null;
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || !ret.equals(m.getReturnType())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != types.length) continue;
                boolean ok = true;
                for (int i = 0; i < types.length; i++) { if (ps[i] != types[i]) { ok = false; break; } }
                if (ok) return m;
            }
        }
        for (Class<?> itf : cls.getInterfaces()) {
            Method m = findExact(itf, name, types, ret);
            if (m != null) return m;
        }
        return null;
    }

    // ============ 反射辅助 ============
    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> c = cls;
        while (c != null) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object getO(Object o, String name) {
        if (o == null) return null;
        try { return findField(o.getClass(), name).get(o); }
        catch (Throwable t) { return null; }
    }
    private static float getF(Object o, String name) {
        if (o == null) return Float.NaN;
        try { return findField(o.getClass(), name).getFloat(o); }
        catch (Throwable t) { return Float.NaN; }
    }
    private static void setF(Object o, String name, float v) {
        if (o == null) return;
        try { findField(o.getClass(), name).setFloat(o, v); }
        catch (Throwable ignored) {}
    }

    // 读取当前“显示中”的 SDR 亮度（RampAnimator.mCurrentValue），用于计算回落速率。
    // 路径：DPC.mScreenBrightnessRampAnimator(DualRampAnimator).mFirst(RampAnimator).mCurrentValue
    private static float readCurrentBrightness(Object dpc) {
        try {
            if (fAnim == null) fAnim = findField(dpc.getClass(), "mScreenBrightnessRampAnimator");
            Object anim = fAnim.get(dpc);
            if (anim == null) return Float.NaN;
            if (fFirst == null) fFirst = findField(anim.getClass(), "mFirst");
            Object first = fFirst.get(anim);
            if (first == null) return Float.NaN;
            if (fCur == null) fCur = findField(first.getClass(), "mCurrentValue");
            return fCur.getFloat(first);
        } catch (Throwable t) { return Float.NaN; }
    }

    /**
     * 判断当前是否为自动亮度模式。
     */
    private static boolean isAutoMode() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Context ctx = null;
            try {
                Object thr = at.getMethod("currentActivityThread").invoke(null);
                if (thr != null) {
                    Method gsc = thr.getClass().getMethod("getSystemContext");
                    gsc.setAccessible(true);
                    Object c = gsc.invoke(thr);
                    if (c instanceof Context) ctx = (Context) c;
                }
            } catch (Throwable ignored) {}
            if (ctx == null) {
                Object app = at.getMethod("currentApplication").invoke(null);
                if (app instanceof Context) ctx = (Context) app;
            }
            if (ctx != null) {
                ContentResolver cr = ctx.getContentResolver();
                return Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                        == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ============ 日志（仅 3 条低频事件输出到 LSPosed，不写文件）============
    private static void log(String line) {
        if (sXp != null) sXp.log(Log.INFO, TAG, line);
    }
}
