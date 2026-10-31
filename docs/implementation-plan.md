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
- Poll the battery state at a modest interval (30–60 seconds); treat kernel mode as authoritative and use 30% / 70% hysteresis.
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
