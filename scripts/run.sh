#!/usr/bin/env bash
# Build, install, launch, and tail logs for the connected phone. M9 has a single launcher entry
# (the conversation list), like P9, so this takes no argument.
set -euo pipefail

cd "$(dirname "$0")/.."

export ANDROID_HOME=/opt/android-sdk

./gradlew :app:installDebug

adb shell am force-stop life.furrer.m9
adb shell am start -n life.furrer.m9/.conversations.ConversationListActivity

PID=""
for _ in $(seq 1 20); do
    PID="$(adb shell pidof life.furrer.m9 || true)"
    [ -n "$PID" ] && break
    sleep 0.2
done

if [ -z "$PID" ]; then
    echo "warning: could not find running process, showing unfiltered logcat" >&2
    exec adb logcat
fi

exec adb logcat --pid="$PID"
