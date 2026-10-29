#!/usr/bin/env bash
# ============================================================
# 亮度解绑 —— 构建脚本
# libxposed API 102 / HyperOS 4 / Android 17 (SDK 37)
#
# 用法：
#   ANDROID_SDK=/path/to/Android/Sdk bash build.sh
#
# 环境变量（均可省略）：
#   ANDROID_SDK   Android SDK 根目录，默认 $HOME/Android/Sdk
#   LSPOSED_API   libxposed API jar，默认 ./libs/libxposed-api.jar
#   KEYSTORE      签名密钥，默认 ./key.jks（缺失则自动生成，且被 .gitignore 排除）
#   KS_ALIAS      密钥别名，默认 brightfix
#   KS_PASS       密钥库/密钥口令，默认 android
# ============================================================
set -e

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SDK="${ANDROID_SDK:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
API_JAR="${LSPOSED_API:-$PROJ/libs/libxposed-api.jar}"

APP=$PROJ/app
SRC=$APP/src
RES=$APP/resources
MANIFEST=$APP/AndroidManifest.xml
RESFLAT_SRC=$APP/res
OUT=$PROJ/out

KEYSTORE="${KEYSTORE:-$PROJ/key.jks}"
KS_ALIAS="${KS_ALIAS:-brightfix}"
KS_PASS="${KS_PASS:-android}"

for f in "$BT/d8" "$BT/aapt2" "$BT/zipalign" "$BT/apksigner" "$ANDROID_JAR" "$API_JAR"; do
  [ -e "$f" ] || { echo "缺少依赖：$f"; exit 1; }
done

# 优先用与 java 同级的 javac，避免 PATH 里的 javac 版本与 JRE 不一致
java_bin="$(command -v java)"
if [ -n "$java_bin" ]; then
  JAVAC="$(dirname "$(dirname "$(readlink -f "$java_bin")")")/bin/javac"
fi
[ -x "$JAVAC" ] || JAVAC="$(command -v javac)"

echo "==> 清理构建目录"
rm -rf "$OUT"
mkdir -p "$OUT/obj" "$OUT/dexout"

echo "==> 1/6 javac 编译"
"$JAVAC" -encoding UTF-8 --release 17 -cp "$ANDROID_JAR:$API_JAR" -d "$OUT/obj" \
  "$SRC/com/unbind/brightness/UnbindBrightnessModule.java"

echo "==> 2/6 d8 转 dex"
find "$OUT/obj" -name '*.class' > "$OUT/classlist.txt"
"$BT/d8" --release --lib "$ANDROID_JAR" --lib "$API_JAR" \
  --output "$OUT/dexout" @"$OUT/classlist.txt"
cp "$OUT/dexout/classes.dex" "$OUT/classes.dex"

echo "==> 3/6 aapt2 编译资源并链接清单"
"$BT/aapt2" compile --dir "$RESFLAT_SRC" -o "$OUT/res.flata"
"$BT/aapt2" link --manifest "$MANIFEST" -I "$ANDROID_JAR" -R "$OUT/res.flata" \
  --min-sdk-version 37 --target-sdk-version 37 --auto-add-overlay -o "$OUT/base.apk"

echo "==> 4/6 注入 classes.dex 与 META-INF/xposed/*"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT" && zip -q -j unsigned.apk classes.dex )
( cd "$RES" && zip -q -r "$OUT/unsigned.apk" META-INF )

echo "==> 5/6 zipalign 对齐"
"$BT/zipalign" -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 6/6 签名"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=unbind-brightness,OU=Dev,O=Local" >/dev/null 2>&1
  echo "    已生成签名密钥：$KEYSTORE"
  echo "    请妥善备份；更换密钥会导致无法覆盖安装升级。"
fi

VER="$(sed -n 's/^version=v\{0,1\}//p' "$RES/META-INF/xposed/module.prop" | head -1)"
VER="${VER:-dev}"
OUT_APK="$OUT/unbind-brightness_${VER}.apk"

"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$OUT_APK" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT_APK" | head -5
echo "==> 完成：$OUT_APK"
