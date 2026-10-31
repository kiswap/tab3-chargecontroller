#!/usr/bin/env bash
set -euo pipefail

if [[ "${SYSTEM_CHANGE_CONFIRMED:-}" != "YES" ]]; then
    echo "This installer writes a persistent init rule to /system." >&2
    echo "Run: SYSTEM_CHANGE_CONFIRMED=YES ./install-system-rule.sh" >&2
    exit 1
fi

ADB="${ADB:-adb}"
LOCAL_RULE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/system/zzz-battery-guardian-permissions.rc"
REMOTE_TEMP="/data/local/tmp/zzz-battery-guardian-permissions.rc"
REMOTE_RULE="/system/etc/init/zzz-battery-guardian-permissions.rc"

"$ADB" wait-for-device
ADB_UID="$("$ADB" shell id -u | tr -d '\r')"
if [[ "$ADB_UID" != "0" ]]; then
    "$ADB" root
    "$ADB" wait-for-device
    ADB_UID="$("$ADB" shell id -u | tr -d '\r')"
fi
if [[ "$ADB_UID" != "0" ]]; then
    echo "ADB shell is not root; enable ADB root in Developer options." >&2
    exit 1
fi

"$ADB" remount
"$ADB" shell "if [ -e '$REMOTE_RULE' ]; then echo 'Refusing to replace existing $REMOTE_RULE'; exit 1; fi"
"$ADB" push "$LOCAL_RULE" "$REMOTE_TEMP"
"$ADB" shell "cp '$REMOTE_TEMP' '$REMOTE_RULE' && chown root:root '$REMOTE_RULE' && chmod 0644 '$REMOTE_RULE' && restorecon '$REMOTE_RULE' && sync && chmod 0666 /sys/class/power_supply/battery/batt_slate_mode && ls -l '$REMOTE_RULE' /sys/class/power_supply/battery/batt_slate_mode"
"$ADB" shell "rm -f '$REMOTE_TEMP'"
echo "Installed the boot rule and enabled the current sysfs node. Reboot to verify persistence."
