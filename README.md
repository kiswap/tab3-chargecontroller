# Battery Guardian

A minimal Java foreground service for the Samsung Galaxy Tab 3 10.1 (`santos103g`). It uses fixed 30% / 70% hysteresis and reports status through an ongoing notification.

## System permission requirement

The charging control is `/sys/class/power_supply/battery/batt_slate_mode`. The tablet originally exposed it as `0664 root:root` and had no usable `su` binary. The installed init rule changes permissions on only that node to `0666` at every boot, allowing the APK to write it without app-level root.

This intentionally means any app on the tablet can enable or disable charging. `install-system-rule.sh` requires `SYSTEM_CHANGE_CONFIRMED=YES`, ADB root, and a successful system remount. A recovery backup is recommended before changing `/system`; the user chose to proceed without one on this fresh install. See [the implementation plan](docs/implementation-plan.md) for device findings.

## Build

The project has no third-party runtime dependencies. The local build script uses the Android SDK command-line tools directly:

```sh
ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./build-apk.sh
```

Override `ANDROID_PLATFORM` or `BUILD_TOOLS_VERSION` if needed. The default output is `build/battery-guardian.apk`, signed with a local debug key. The APK has minimum and target SDK 25 and contains Java bytecode only (no ABI-specific native libraries).

## Install and start

Normally, make a recovery backup of boot, system, and data before installing the rule. For this fresh install, the user chose to proceed without one:

```sh
SYSTEM_CHANGE_CONFIRMED=YES ./install-system-rule.sh
```

The installer requires ADB root, remounts `/system`, installs `system/zzz-battery-guardian-permissions.rc`, and temporarily applies the same permission to the live node. Then reboot to verify init reapplies it at startup:

```sh
adb reboot
```

After reboot, confirm the node is world-writable, then install the app:

```sh
adb shell ls -l /sys/class/power_supply/battery/batt_slate_mode
adb install -r build/battery-guardian.apk
```

Open **Battery Guardian** once from the launcher to start its foreground service. It also requests service startup after `BOOT_COMPLETED`. Its only persistent user interface is the notification.

The expected permissions are `-rw-rw-rw-`. Open the app only when ready for it to manage charging; it may pause charging immediately if the battery is already at least 70%.

## Controller behavior

- Polls battery capacity, charger presence, and the kernel mode every 60 seconds.
- At 70% or above, changes slate mode from `0` to `1`; at 30% or below, changes it from `1` to `0`.
- Preserves the kernel mode between those thresholds.
- Clears slate mode when the physical charger is reported disconnected.
- Verifies every mode write by reading the value back.
- Uses the read-only Android battery broadcast for a percentage when privileged control is unavailable.

The service attempts to restore mode `0` on a graceful stop, but an APK cannot guarantee recovery after a hard process kill. Recovery remains available through ADB:

```sh
adb shell 'echo 0 > /sys/class/power_supply/battery/batt_slate_mode'
```

To remove the persistent permission rule, boot Android, then run:

```sh
adb root
adb remount
adb shell 'rm /system/etc/init/zzz-battery-guardian-permissions.rc; chmod 0664 /sys/class/power_supply/battery/batt_slate_mode; sync'
adb reboot
```

Do not treat `sec-charger/online` as an indication that the battery is charging; the kernel mode and battery status are the relevant state.

## Validation status

The production APK was built and signature-verified for API 25. Before the rule was installed, a temporary app run confirmed it reports the node as not writable and leaves mode `0` unchanged. After installation, a reboot confirmed the init rule persisted (`0666`), `/system` returned to read-only, and the foreground service restarted from boot. A temporary stop build wrote mode `1` and produced `Discharging` with the charger still connected. A temporary resume build then wrote mode `0` and restored `Charging`. The production APK was rebuilt with 30% / 70% thresholds and reinstalled; the tablet is currently charging at 67%. Charger removal and hard-process recovery still need validation.
