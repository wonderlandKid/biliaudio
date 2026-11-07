#!/usr/bin/env bash
# 启动 headless 模拟器（用于截图走查 / 冒烟测试）。
# 依赖：$ANDROID_SDK_ROOT 下的 emulator、platform-tools，以及名为 biliaudio_api36 的 AVD。
# 用法：scripts/emulator.sh [start|stop|status]
set -uo pipefail

SDK="${ANDROID_SDK_ROOT:-/home/wonderlandkid/code/software_bin/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVD="${AVD_NAME:-biliaudio_api36}"

case "${1:-start}" in
  start)
    if "$ADB" devices | grep -q "emulator-5554.*device"; then
      echo "emulator already running"
      exit 0
    fi
    nohup "$EMULATOR" -avd "$AVD" -no-window -no-audio -no-snapshot -no-boot-anim \
      -gpu swiftshader_indirect -no-metrics > /tmp/biliaudio-emulator.log 2>&1 &
    echo "starting $AVD ... (log: /tmp/biliaudio-emulator.log)"
    "$ADB" wait-for-device
    for i in $(seq 1 90); do
      boot=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
      if [ "$boot" = "1" ]; then echo "boot completed"; exit 0; fi
      sleep 2
    done
    echo "boot timeout"; exit 1
    ;;
  stop)
    "$ADB" emu kill 2>/dev/null || true
    echo "emulator stopped"
    ;;
  status)
    "$ADB" devices
    ;;
  *)
    echo "usage: $0 [start|stop|status]"; exit 1
    ;;
esac
