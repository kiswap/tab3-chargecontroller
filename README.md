# Battery Guardian

A minimal Java foreground service for the Samsung Galaxy Tab 3 10.1 (`santos103g`). It uses fixed 50% / 70% hysteresis and reports status through an ongoing notification.

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

- Polls battery capacity, AC/USB input presence, and the kernel mode every 60 seconds. `sec-charger/online` is logged as a raw driver value, not treated as a boolean charger-present flag.
- At 70% or above, changes slate mode from `0` to `1`; at 50% or below, changes it from `1` to `0`.
- While mode `1` is set, detects positive average battery current on two consecutive polls and reapplies mode `1`, with a five-minute cooldown between retries. This reissues the kernel's charger-off action if an input-source event resumes charging without clearing the mode flag.
- Preserves the kernel mode between those thresholds.
- Keeps slate mode latched across a reported disconnect, since this kernel can report USB offline as a result of pausing charge; releases the pause at 50% even while disconnected.
- After resuming at 50%, checks battery current. If current remains negative with a source present, it performs one brief slate-mode reset and reports whether charging is confirmed; if not, it asks for a cable replug.
- Verifies every mode write by reading the value back.
- Uses the read-only Android battery broadcast for a percentage when privileged control is unavailable.
- Records each poll's capacity, slate mode, charger value, kernel battery status, current readings, action, and mode readback to a rolling diagnostic log. The log is capped at about 1 MiB across the current and previous files and is also written to logcat.

Retrieve the diagnostic history after reconnecting ADB:

```sh
adb pull /sdcard/Android/data/org.chargeguardian/files/diagnostics/battery-guardian.log
adb pull /sdcard/Android/data/org.chargeguardian/files/diagnostics/battery-guardian.log.1
```

Each line is timestamped. `mode=1` means slate mode is requested; compare that with `battery_status`, `current_now`, `current_avg`, and capacity over time to verify what the charger and battery actually do. The current readings' units and near-zero interpretation have not been verified for this exact kernel, so use sustained readings and capacity trends rather than one sample.

### Checking the app against a USB meter

A RuiDeng UM34C USB meter between the charger and the tablet measures the current the tablet really pulls from the charger. The scripts in `tools/` need only Python 3 on a Linux laptop with Bluetooth. Turn on the meter's Bluetooth switch and pair it once with `bluetoothctl` (PIN 1234).

`tools/um34c_logger.py` reads the meter once per second and appends to a CSV file. It reconnects by itself after a Bluetooth dropout, and the outage shows up as a gap in the timestamps. Stop it with Ctrl+C.

```sh
tools/um34c_logger.py --address 00:15:A3:00:36:83 --output meter.csv
```

`tools/compare_charging.py` merges the meter CSV with the app's diagnostic logs and prints a report. The report shows the meter current per app state, the paused periods in which the meter still saw current above a threshold (0.05 A by default), each charge cycle with the battery level at its start and end and the energy delivered, and the meter current before and after each mode write by the app.

```sh
tools/compare_charging.py meter.csv battery-guardian.log.1 battery-guardian.log --merged merged.csv
tools/compare_charging.py meter.csv --adb    # pulls both logs from the tablet first
```

Both tools use local time, so a few seconds of clock difference between the laptop and the tablet do not matter. Use `--offset` if the tablet clock is clearly wrong.

Mode 1 counts as paused in the report even when the charger reads offline, because slate mode itself makes the AC and USB nodes report offline. The meter facts, the frame format and the pass criteria are in section 16 of `docs/battery_guardian_codex_handover.md` and in `docs/implementation-plan.md`.

Some powerbanks shut their output off when the tablet stops drawing charge. Battery Guardian can clear slate mode when AC/USB input disappears, but it cannot turn a powerbank back on if the bank requires a button press or cable reconnect. Use a continuously-on charger or a powerbank with an always-on mode for unattended 30%/70% control.

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
