#!/usr/bin/env bash
# 在 headless 模拟器上安装最新 debug APK、启动 App 并截图到 docs/screenshots/。
# 用法：
#   scripts/screenshot.sh <输出文件名> [等待秒数] [adb shell 额外命令]
# 示例：
#   scripts/screenshot.sh home 8
#   scripts/screenshot.sh album 'am start -a android.intent.action.VIEW -d "biliaudio://album/1"'
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/home/wonderlandkid/code/software_bin/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$PROJECT_DIR/docs/screenshots"
NAME="${1:-screenshot}"
WAIT="${2:-6}"

mkdir -p "$OUT_DIR"
"$ADB" install -r "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
"$ADB" shell pm grant com.wonderlandkid.biliaudio android.permission.POST_NOTIFICATIONS 2>/dev/null || true
"$ADB" shell am force-stop com.wonderlandkid.biliaudio
"$ADB" shell am start -W -n com.wonderlandkid.biliaudio/.MainActivity >/dev/null
sleep "$WAIT"
"$ADB" exec-out screencap -p > "$OUT_DIR/$NAME.png"
echo "screenshot saved: $OUT_DIR/$NAME.png"
