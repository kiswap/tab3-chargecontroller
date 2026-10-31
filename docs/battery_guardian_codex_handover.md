# Battery Guardian — Codex Project Handover

**Purpose:** Start a separate Codex session to design, implement, build, and test a minimal Android charging-management service for a Samsung Galaxy Tab 3 10-inch tablet.

**Document status:** Handover based on observations and experiments performed on 2026-10-31. Facts, hypotheses, and open questions are distinguished below.

---

## 1. Project objective

Build a very lightweight Android application for a Samsung Galaxy Tab 3 10-inch (`santos103g`) running LineageOS 14.1 / Android 7.1.2.

The app should automatically keep the battery within a configurable-in-source (hardcoded for now) range:

- **Stop charging at 70%.**
- Allow the tablet to discharge while the charger remains physically connected.
- **Resume charging at 30%.**
- Continue repeating this cycle.

The user explicitly wants a minimal, headless application:

- No conventional UI, settings screen, history, charts, or database.
- A persistent notification is the only user-facing interface.
- Notification should show whether charging is enabled / the tablet is discharging, and preferably current battery percentage.
- No user-configurable thresholds for now; hardcode 30% and 70%.
- Keep dependencies and runtime overhead as low as possible.

A native Java implementation is preferred. Target Android 7.1.2 / API 25 compatibility.

## 2. Device and software facts (verified)

| Property | Observed value |
|---|---|
| Device codename | `santos103g` |
| Device | Samsung Galaxy Tab 3 10-inch, 3G variant (device codename identifies this variant) |
| Android release | `7.1.2` |
| ROM | LineageOS 14.1, unofficial build |
| Build date shown in kernel | Mon Apr 26 08:02:59 CEST 2021 |
| Kernel | `3.4.34-lineage-01570-g463c91c87b07` |
| Kernel architecture | `i686` |
| Kernel build string | `#1 SMP PREEMPT` |
| Build type | `userdebug` |
| `ro.debuggable` | `1` |
| SELinux mode | `Permissive` |
| Root-capable ADB | Yes, after enabling root for ADB in Developer options |

Commands and outputs:

```sh
adb shell getprop ro.product.device
# santos103g

adb shell getprop ro.build.version.release
# 7.1.2

adb shell uname -a
# Linux localhost 3.4.34-lineage-01570-g463c91c87b07
# #1 SMP PREEMPT Mon Apr 26 08:02:59 CEST 2021 i686
```

Build properties:

```sh
adb shell getprop ro.build.type
# userdebug

adb shell getprop ro.debuggable
# 1

adb shell getenforce
# Permissive
```

### ADB root behaviour

Initially `su` was not installed:

```text
/system/bin/sh: su: not found
```

The user enabled root for ADB in Developer options. `adb root` printed messages such as:

```text
restarting adbd as root
```

but appeared to hang / not return to the host prompt. Unplugging and reconnecting USB was needed. After that, this worked:

```sh
adb shell id
```

Output included:

```text
uid=0(root) gid=0(root) groups=0(root),...
context=u:r:su:s0
```

This proves that **ADB shell can be root**. It does **not** prove that an ordinary installed Android application can obtain root. The application privilege strategy remains an important open design issue.

## 3. Critical discovery: Samsung slate-mode charging control

The kernel exposes a Samsung-specific sysfs attribute:

```text
/sys/class/power_supply/battery/batt_slate_mode
```

It was readable and displayed `0` initially.

The sysfs permission was:

```text
-rw-rw-r-- 1 root root ... batt_slate_mode
```

Therefore it is writable by root (owner) and root group, but not by the ordinary `shell` user. SELinux being permissive does not override Unix file permissions.

**Verified behaviour from actual tests:**

- Writing `1` disables battery charging and puts the battery into discharging state.
- Writing `0` restores normal charging.
- Charging resumed immediately after writing `0`.

Commands used:

```sh
# Disable battery charging
echo 1 > /sys/class/power_supply/battery/batt_slate_mode

# Re-enable normal charging
echo 0 > /sys/class/power_supply/battery/batt_slate_mode
```

### Before / after observations

With charger connected, before enabling slate mode:

```text
batt_slate_mode: 0
battery/status: Charging
battery/capacity: 71
usb/online: 1
sec-charger/online: 3
sec-charger/status: Charging
```

After:

```sh
echo 1 > /sys/class/power_supply/battery/batt_slate_mode
```

Observed:

```text
battery/status: Discharging
battery/capacity: 71
batt_slate_mode: 1
usb/online: 0
sec-charger/online: 3
sec-charger/status: Discharging
```

After restoring:

```sh
echo 0 > /sys/class/power_supply/battery/batt_slate_mode
```

Observed:

```text
batt_slate_mode: 0
battery/status: Charging
sec-charger/status: Charging
```

### Additional power-path test

With slate mode enabled and the charger physically still connected, after 10 seconds:

```text
batt_current_ua_now: -100781
batt_current_ua_avg: -84687
voltage_now: 3888000
battery/status: Discharging
sec-charger/online: 3
usb/online: 0
```

After a few minutes:

```text
batt_current_ua_now: -92031
batt_current_ua_avg: -89843
voltage_now: 3887000
battery/status: Discharging
sec-charger/online: 3
usb/online: 0
```

Interpretation:

- Negative current values indicate the battery is supplying current.
- The tablet consumes roughly 90 mA in this observed idle state while slate mode is enabled.
- The charger remains physically connected; `sec-charger/online` remains `3`.
- `usb/online` changes to `0`.
- The tablet is **not** being powered directly from the adapter in slate mode, at least under the tested conditions; it is actually discharging the battery.

This behaviour is acceptable for the user's desired 30–70% cycle, but should be documented. It also means a continuously connected charger does not imply the tablet is externally powered while slate mode is enabled.

**Important:** Do not assume `sec-charger/online=3` means charging is occurring. The status and battery current showed actual discharge.

## 4. Battery and power-supply sysfs interfaces

Power supplies present:

```text
/sys/class/power_supply/ac
/sys/class/power_supply/battery
/sys/class/power_supply/ps
/sys/class/power_supply/sec-charger
/sys/class/power_supply/sec-fuelgauge
/sys/class/power_supply/usb
```

Useful observed paths:

| Path | Meaning / observed use |
|---|---|
| `/sys/class/power_supply/battery/capacity` | Battery percentage; observed `71` |
| `/sys/class/power_supply/battery/status` | `Charging` / `Discharging` |
| `/sys/class/power_supply/battery/batt_slate_mode` | **Charging control**; `0` normal charging, `1` slate mode / charging disabled |
| `/sys/class/power_supply/battery/batt_current_ua_now` | Current in microamps; negative while discharging |
| `/sys/class/power_supply/battery/batt_current_ua_avg` | Average current in microamps |
| `/sys/class/power_supply/battery/voltage_now` | Battery voltage in microvolts; observed ~3,887,000 |
| `/sys/class/power_supply/battery/temp` | Temperature; initial observed `291` (likely tenths °C = 29.1 °C; confirm units before relying on it) |
| `/sys/class/power_supply/usb/online` | USB power state; changed from `1` to `0` in slate mode |
| `/sys/class/power_supply/sec-charger/online` | Charger online value; observed `3` |
| `/sys/class/power_supply/sec-charger/status` | Charging / Discharging |
| `/sys/class/power_supply/sec-fuelgauge/capacity` | Fuel-gauge percentage; observed `71` |

The initial full battery sysfs dump also showed:

```text
health: Good
technology: Li-ion
present: 1
charge_type: Fast
```

The `battery/capacity` file is read-only (`-r--r--r--`), as expected. `batt_slate_mode` was `-rw-rw-r--`, so root can write it.

Do not assume every writable-looking Samsung-specific sysfs attribute is safe to manipulate. The only control tested so far is `batt_slate_mode`.

## 5. Kernel source lead

A potentially relevant kernel source repository was identified:

- Repository: `https://github.com/santos10-dev/android_kernel_samsung_santos10`
- Branch mentioned: `cm-14.1`
- Relevant driver: `drivers/battery/sec_battery.c`
- Relevant function mentioned during investigation: `sec_bat_store_attrs()`

A previous inspection suggested that the `batt_slate_mode` handler:
- accepts `1` to enable slate mode / disable normal battery charging;
- accepts `0` to disable slate mode / restore normal USB charging source.

**Codex must independently verify this against the exact branch and installed build before relying on the source interpretation.** The runtime experiments above are the strongest evidence of actual behaviour.

## 6. Proposed application behaviour

### Hysteresis algorithm

Use two thresholds, not a single threshold:

```text
STOP_CHARGING_AT = 70
START_CHARGING_AT = 30
```

Maintain a state variable representing whether charging is currently enabled.

Pseudo-code:

```text
on startup:
    read persisted controller state if valid
    inspect actual batt_slate_mode value
    reconcile persisted and kernel state safely
    start foreground notification
    begin monitoring

periodically:
    read battery capacity
    read batt_slate_mode
    read charger / battery status

    if charger is disconnected:
        do not attempt to charge
        ensure state is safe and reflect actual state

    if charging is enabled and capacity >= 70:
        write "1" to batt_slate_mode
        verify write by reading it back
        update notification to "Discharging / charging paused"

    else if charging is disabled and capacity <= 30:
        write "0" to batt_slate_mode
        verify write by reading it back
        update notification to "Charging"

    otherwise:
        retain current state

    periodically refresh notification with current percentage
```

The controller must not oscillate around one threshold. It must preserve the last mode while capacity is between 30% and 70%.

### Notification

The persistent foreground-service notification is the only UI.

Examples:

- **Charging enabled**
  - Title: `Battery Guardian`
  - Text: `Charging · 42% (stops at 70%)`

- **Charging paused**
  - Title: `Battery Guardian`
  - Text: `Discharging · 68% (resumes at 30%)`

Notification should be low-noise and update only when meaningful state changes occur or at a modest periodic interval. Use an appropriate small status-bar icon if practical. A tap action can optionally open Android app info or do nothing; no full activity is required.

No history, settings, charts, database, analytics, network access, or unnecessary permissions.

## 7. Architecture preferences

Preferred implementation:

- Java (not Kotlin).
- Native Android SDK; no third-party libraries unless essential.
- Minimum / target SDK compatible with Android 7.1.2 (API 25). The app must install and run on this device. Consider modern target-SDK restrictions only if they do not compromise API 25 compatibility.
- One foreground service.
- Minimal battery polling interval; e.g. 30–60 seconds is likely adequate. Avoid frequent wakeups. Investigate whether `ACTION_BATTERY_CHANGED` can be used as a supplement, but sysfs polling is required for the control.
- `START_STICKY` may be appropriate, but test actual restart behaviour.
- `BOOT_COMPLETED` receiver to start the service after boot, with required manifest permission.
- A small amount of persistent state (SharedPreferences or a tiny state file) is acceptable; no database. The user said no history, not that state persistence is forbidden.
- Root-level sysfs access must be implemented explicitly.

### Privilege strategy — unresolved and critical

ADB root is confirmed, but app root is not.

Do **not** assume the application can simply run `su -c`. The `su` command was initially absent:

```text
/system/bin/sh: su: not found
```

The ROM's Developer options provide an ADB root mode, which results in root ADB shell. Determine whether the ROM has an app-root mode or a supported `su` binary elsewhere, but don't install Magisk or other root packages without necessity and user consent.

Possible strategies to investigate:

1. **ROM-provided app root / `su`:** Check whether Developer options has a separate Apps or Apps and ADB root mode. Check standard `su` locations only after verifying.
2. **Small privileged native helper / init service:** Since this is a `userdebug` ROM and ADB root is available, investigate whether a persistent init-launched root daemon or helper can be added. This may require a ROM/system image change and is more invasive.
3. **ADB-root deployment as a development-only proof of concept:** ADB can write the sysfs node, but an attached computer is not a valid production runtime dependency.
4. **Custom ROM build integration:** If needed, add a tiny dedicated root helper service to the existing ROM build. Prefer this over a large root framework if practical.

Do not ship an app that silently claims to manage charging if it lacks permission to write the control node. The service must detect permission failure and clearly report `Control unavailable` in the notification/logcat.

## 8. Reliability and safety requirements

This is a battery charging controller, so fail-safe behaviour matters.

- Never leave slate mode enabled accidentally after an app crash or upgrade without a clear recovery mechanism.
- On unexpected exceptions, invalid sysfs values, or write failures, prefer restoring normal charging (`batt_slate_mode=0`) when it is safe to do so.
- Verify every sysfs write by reading the value back.
- On service startup, reconcile the actual kernel state with intended policy.
- Handle reboot. Determine whether `batt_slate_mode` resets at boot; do not assume.
- Handle charger removal and reconnection.
- Do not attempt to force charging if no charger is connected.
- Consider battery temperature safety. First verify the units and valid range of `battery/temp`; don't invent thresholds. The hardware/PMIC should retain its own protections.
- Provide a manual recovery command in documentation:

```sh
adb root
adb shell 'echo 0 > /sys/class/power_supply/battery/batt_slate_mode'
```

## 14. Notification alternation fix (2026-10-31)

After installation, the foreground notification alternated about every 30 seconds between `Discharging` and `Control unavailable` while the battery percentage remained at 42% and slate mode remained `1`.

Root cause: every `ACTION_BATTERY_CHANGED` broadcast called `updateUnavailableNotice()`, overwriting the valid controller status. The controller itself polls every 60 seconds, so the next poll replaced the false unavailable message with `Discharging` again. The broadcasts are more frequent than the poll interval on this tablet.

The receiver now only updates the latest broadcast percentage; it no longer changes the controller status. The normal controller poll remains responsible for status notifications. SELinux audit lines seen during diagnosis had `permissive=1`, so SELinux was logging those accesses but not enforcing the reported denials.

The corrected APK was built, installed on the connected GT-P5200, and the foreground service relaunched. Immediately afterward, the notification showed `Discharging · 42% (resumes at 30%)` and `batt_slate_mode` remained `1`. Long-duration confirmation that the alternating notification has stopped is still pending from the user.

If `adb root` hangs during adbd restart, wait for USB reconnect or unplug/replug, then run `adb shell` again. This was observed on the tablet.
- Consider a watchdog/recovery mechanism if the app is killed while slate mode is enabled. Avoid creating an aggressive wake lock or excessive polling.
- The controller should be idempotent: writing the same mode should not create harmful repeated transitions.

## 9. Development and verification plan

Proceed incrementally; do not implement the full app before validating privilege and recovery design.

### Stage 1 — inspect exact platform and source
- Confirm device tree / kernel source branch and charging driver.
- Confirm `batt_slate_mode` semantics from source.
- Check whether the ROM exposes app root separately from ADB root.
- Check if slate mode resets on reboot.
- Verify temperature units and charger-disconnect behaviour.

### Stage 2 — minimal root controller proof of concept
- Implement a tiny helper or service that can read battery percentage and write `batt_slate_mode`.
- Test read/write and read-back verification.
- Test 70% stop and 30% resume logic without UI.
- Test charger disconnect/reconnect.
- Test recovery after process termination.

### Stage 3 — foreground service and notification
- Implement Java service.
- Persistent notification reports `Charging` or `Discharging` and battery percentage.
- Start at boot.
- Ensure service restarts safely.
- Keep dependencies minimal.

### Stage 4 — build and test
- Build an APK installable on API 25 / i686.
- Verify package architecture and compatibility; avoid native code if possible.
- Test on actual tablet, not just emulator.
- Test threshold transitions using temporarily adjusted test thresholds or a controlled test harness; restore production constants to 30/70.
- Verify app/service restart, device reboot, charger removal, and manual recovery.
- Provide source project and installable APK, plus concise installation/root setup instructions.

## 10. Useful commands

Connect:

```sh
adb devices
adb shell
```

Check root:

```sh
adb shell id
# Expected uid=0(root) after enabling ADB root in Developer options
```

Inspect core state:

```sh
cat /sys/class/power_supply/battery/capacity
cat /sys/class/power_supply/battery/status
cat /sys/class/power_supply/battery/batt_slate_mode
cat /sys/class/power_supply/battery/batt_current_ua_now
cat /sys/class/power_supply/battery/batt_current_ua_avg
cat /sys/class/power_supply/battery/voltage_now
cat /sys/class/power_supply/battery/temp
cat /sys/class/power_supply/usb/online
cat /sys/class/power_supply/sec-charger/online
cat /sys/class/power_supply/sec-charger/status
```

Disable charging:

```sh
echo 1 > /sys/class/power_supply/battery/batt_slate_mode
```

Restore charging:

```sh
echo 0 > /sys/class/power_supply/battery/batt_slate_mode
```

Install an APK:

```sh
adb install -r path/to/app-debug.apk
```

Observe logs:

```sh
adb logcat
```

## 11. Key facts versus open questions

### Verified facts

- Device codename is `santos103g`.
- Android is 7.1.2 / LineageOS 14.1.
- Kernel is 3.4.34, i686.
- ROM is `userdebug`, `ro.debuggable=1`, SELinux permissive.
- ADB root works after enabling it in Developer options, though adbd restart may hang until USB reconnect.
- Root can write `batt_slate_mode`.
- `batt_slate_mode=1` changes battery status to Discharging and battery current becomes negative.
- `batt_slate_mode=0` restores Charging.
- Charger is physically still plugged in during slate mode.
- In slate mode, observed battery discharge was approximately 90 mA.
- `usb/online` changed to 0 while `sec-charger/online` remained 3.
- User wants a minimal Java foreground service and notification only, with hardcoded 30% / 70% thresholds.

### Open questions Codex must resolve

1. How can an installed app obtain the required root privilege on this exact ROM?
2. Does the ROM offer app root separately from ADB root?
3. Does slate mode reset after reboot?
4. What exactly happens to power routing when slate mode is enabled? Current evidence shows the battery discharges.
5. What are the units and safe interpretation of `battery/temp`?
6. What should happen when the charger is unplugged while slate mode is enabled?
7. What service-start and reboot behaviour is reliable on Android 7.1.2?
8. Is an init-integrated root helper required, and if so, what is the least invasive maintainable way to provide it?

---

## 12. Instructions to Codex

You are taking over this project from a separate planning/research session. Start by reviewing this handover and independently verifying the critical kernel and privilege assumptions.

**Do not immediately generate a large application.** First produce a concise technical implementation plan addressing the app-root problem, service lifecycle, sysfs control, state recovery, and testing. Clearly identify which parts can be delivered as a standalone APK and which parts, if any, require ROM/system integration.

Keep the product intentionally minimal: no UI beyond a persistent notification, no history, no settings, no database, and no unnecessary dependencies. The thresholds are fixed at 30% and 70% for the first version.

Do not claim success until an APK has been built and tested on the actual tablet. Include the full source project, build instructions, APK, installation instructions, and recovery procedure in the final deliverables.

---

## 13. Implementation status addendum (2026-10-31)

The first implementation is in place and running on the connected `santos103g` tablet.

- The app uses direct sysfs access; it does not need app-level `su`.
- `/system/etc/init/zzz-battery-guardian-permissions.rc` runs at `post-fs-data` and applies `chmod 0666` only to `/sys/class/power_supply/battery/batt_slate_mode`. This permission is intentionally world-writable, so any app on the device can toggle charging.
- The user explicitly chose to proceed without a recovery backup because this was a fresh install. The rule was installed with ADB root; after reboot, it persisted and `/system` remounted read-only.
- The foreground service started again after reboot. The APK targets API 25 and is built by `build-apk.sh`; installation, threshold stop/resume writes, mode readback, reboot persistence, and production 30% / 70% restoration were verified on the tablet.
- A temporary stop-threshold build changed slate mode to `1` and the battery reported `Discharging` while the charger remained connected. A temporary resume-threshold build changed it back to `0` and charging resumed. The production APK was restored afterward. At final verification, slate mode was `0` and the tablet was charging.
- `README.md` contains build, installation, behavior, and manual recovery instructions. `docs/implementation-plan.md` records platform findings and remaining work.

Still unverified: charger removal/reconnection behavior in the app, recovery after a hard process kill, temperature units, and the exact installed kernel source handler. A manual recovery command remains:

```sh
adb shell 'echo 0 > /sys/class/power_supply/battery/batt_slate_mode'
```
