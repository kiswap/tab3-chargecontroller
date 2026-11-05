# Battery Guardian implementation plan

## Current platform findings (2026-10-31)

- Connected device is `GT_P5200` / `santos103g`, Android 7.1.2, `userdebug`.
- ADB root was available for the system change. After reboot, `adb shell` is currently uid 2000; app control no longer depends on ADB root.
- No `su` executable was found by PATH lookup or search at `/system/bin/su`, `/system/xbin/su`, `/sbin/su`, or `/vendor/bin/su`; no root-management package was listed. An actual app launch confirmed its `su` execution fails with `Permission denied`.
- Before the change, `/sys/class/power_supply/battery/batt_slate_mode` was `0664 root:root`. The installed init rule now keeps it `0666 root:root`; latest state is mode `0`, capacity `67%`, status `Charging`, charger online `3`.
- Temperature currently reads `300`. Its units and safe interpretation have not been verified for this exact kernel.
- The user explicitly authorized the `/system` change without a recovery backup on this fresh install. After installation, the permission change persisted across reboot and `/system` returned to read-only. A temporary threshold test wrote mode `1`; mode `0` was restored afterward.
- A local Android SDK is present (platform 36; build-tools 35.0.0 and 36.1.0), but no Gradle executable or cached Android Gradle Plugin was found in the initial inspection.

## Permission implementation

The app uses direct sysfs access and does not require `su`. The installed init rule at `/system/etc/init/zzz-battery-guardian-permissions.rc` grants write permission on only this sysfs node at every boot. This intentionally allows any app on the tablet to change charging mode.

The rule is installed at `/system/etc/init/zzz-battery-guardian-permissions.rc`. The installer used `SYSTEM_CHANGE_CONFIRMED=YES` after the user explicitly chose to proceed without a recovery backup.

## App design

- Java-only Android app, minimum API 25, no third-party runtime dependencies.
- One foreground service with a persistent notification, started at boot and restarted with `START_STICKY` where appropriate.
- Poll the battery state at a modest interval (30–60 seconds); treat kernel mode as authoritative and use 50% / 70% hysteresis.
- Do not enable charging while no charger is connected. On disconnect, avoid forcing a mode and show observed state.
- Open the control node for writing before reporting control as available. Verify every mode write by reading it back. Report unavailable access, invalid readings, or write failure clearly; do not claim active control.
- Persist only the minimum controller state needed to preserve hysteresis. On startup, reconcile it with the kernel's actual state.
- Include manual ADB recovery instructions to write `0` to `batt_slate_mode`.

## Remaining gates

1. Inspect the exact installed kernel source/build and verify the attribute handler, boot reset behavior, and charger-removal behavior. The related LineageOS `cm-14.1` driver exposes the attribute and its slate-mode path reports discharging, but it is not the exact cited repository/build, so it is corroboration rather than proof.
2. Verify temperature units only if temperature is to affect policy; the first version should leave PMIC temperature protections intact and not invent an app threshold.
3. Test charger removal and recovery after a hard process kill. Both controller directions were exercised with temporary thresholds at the device's current charge level; production 30% / 70% constants and APK were restored afterward.

## Source lead

The cited `santos10-dev/android_kernel_samsung_santos10` `cm-14.1` source page could not be fetched in this session. A related LineageOS `cm-14.1` driver shows `batt_slate_mode` in the Samsung battery attributes and slate mode assigning battery status to discharging, but the exact handler still needs verification against the installed build.

## Follow-up: notification status fix (2026-10-31)

The notification had alternated every ~30 seconds between `Discharging` and `Control unavailable`. The battery broadcast receiver was incorrectly reporting control unavailable on every `ACTION_BATTERY_CHANGED` event; a 60-second controller poll then restored the correct status. The receiver now updates only the cached percentage, leaving controller status updates to the poll. The corrected APK was built and installed, and the foreground notification initially showed `Discharging · 42% (resumes at 30%)` with `batt_slate_mode=1`. Observe the tablet to confirm the alternation has stopped.

## Follow-up: persistent charging diagnostics (2026-11-02)

The service writes one diagnostic record per 60-second poll to `Android/data/org.chargeguardian/files/diagnostics/battery-guardian.log`, rotating at 512 KiB and keeping one prior file. Records include capacity, `batt_slate_mode`, charger-online value, AC/USB online values, battery status, current readings, controller action, and slate-mode readback. They also appear in logcat under `BatteryGuardian` with the `DIAG` prefix.

The log caught a failure on the normal charger: capacity rose from 85% to 90% while mode stayed 1 and current_avg remained positive for about 26 minutes. The app reported `hold` each minute because it only issued the slate-mode write when the mode bit changed. The related `santos10-dev/android_kernel_samsung_santos10` `cm-14.1` source shows writing 1 sets slate mode, sets battery online to battery, and sets `sec-charger` `POWER_NOW` to 0. It does not establish that the installed kernel binary is identical.

The controller now reapplies mode 1 after two consecutive positive `current_avg` readings while a charger is online, then at most every five minutes if charging current persists. This is intended to repeat the kernel charger-off action after a source event. The notification warns `Charging despite pause` while positive current is observed. The updated APK was built and installed at 11:04 on 2026-11-02. Its initial computer-USB sample was capacity 90%, mode 1, AC online 0, USB online 1, current_avg -179. Verify the mitigation with the normal wall charger; that source has not yet been retested after installation.

## Follow-up: powerbank shutoff and source detection (2026-11-03)

The saved diagnostic history explains the discharge. On 2026-11-03 at 10:18–10:19, AC input was online with positive average current while mode 1 was set; the controller reasserted mode 1. At the next poll, both `ac/online` and `usb/online` were 0, battery status was `Discharging`, and current was negative. The tablet then declined from 55% to 30% while no external input was detected. At 30%, the controller correctly wrote mode 0, but AC/USB stayed offline and capacity continued down to 6%; the service later restarted at 12% after power returned. This is consistent with the powerbank cutting its output after charging was paused, or with a physical disconnect. The log cannot distinguish those two cases.

The root bug was treating `sec-charger/online` as a boolean; its nonzero value persisted while both actual AC and USB input nodes were zero. The controller now uses `ac/online` or `usb/online` for source presence, with `ACTION_BATTERY_CHANGED` plug state as fallback only if both sysfs nodes are unavailable. Follow-up logs on 2026-11-04 showed slate mode itself caused AC/USB and Android plug state to report offline, so clearing mode on every apparent disconnect made the controller resume charging right after the 70% pause. The controller now keeps slate mode latched across disconnect reports and releases it at the 50% resume threshold. This may leave the tablet paused after a genuine unplug until it reaches 50%; this is consistent with maintaining the selected hysteresis state. The app cannot restart a powerbank that requires a button press or cable reconnection.

The correction was built and installed at 15:27 on 2026-11-03. Immediately afterward the tablet reported 12%, mode 0, AC offline, USB online, and negative average current while connected to the computer. A reliable wall charger is needed before leaving the tablet unattended.

## Follow-up: meter-based validation (2026-11-04)

The app's diagnostic log cannot show on its own whether the charger still delivers power during a pause. The reason is that slate mode makes the AC and USB nodes report offline. So a RuiDeng UM34C USB meter now sits between the wall charger and the tablet. The laptop logs the meter over Bluetooth with `tools/um34c_logger.py`. Afterwards, `tools/compare_charging.py` merges that log with `battery-guardian.log`. The meter facts and the frame format are in section 16 of `battery_guardian_codex_handover.md`.

The run counts as a pass if both of these hold over at least one full cycle:

- While mode 1 is set, the meter current stays near zero. The compare script uses 0.05 A as its threshold. If the meter shows a steady current instead, check `current_avg` for the same period. A negative `current_avg` means the charger runs the system while the battery is not charged. A positive `current_avg` means the charge has resumed and the pause failed.
- The app writes mode 1 at 70% and mode 0 at 50%. The meter current drops within about 10 seconds after mode 1 is written, and it rises again after mode 0 is written.

The first run started on 2026-11-04. Its results are still pending.

## Follow-up: resume recovery attempt (2026-11-05)

Added a one-time charge restart pulse after resuming at 50% if two consecutive average-current samples remain negative while a source is present: briefly write slate mode 1, then restore mode 0, verifying both writes. The service then waits for positive average current and distinguishes charge allowed from charging confirmed in its notification. If current stays negative after the pulse, it reports that charging was not detected and prompts a cable replug. The APK was built and installed on 2026-11-05; the tablet was at 57% on laptop USB, so the recovery path was not triggered. Wall-charger validation at a resume point is still required. Details and exact next steps are in the `Follow-up: resume recovery attempt (2026-11-05)` section of `battery_guardian_codex_handover.md`.
