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

## 15. Charging diagnostics (2026-11-02)

The service emits a rolling diagnostic file at `/sdcard/Android/data/org.chargeguardian/files/diagnostics/battery-guardian.log` and `.log.1`, and mirrors records to logcat as `BatteryGuardian` / `DIAG`. Every poll records capacity, slate mode, charger-online value, AC/USB online flags, kernel battery status, current readings, controller action, and mode readback. The current file rotates at 512 KiB; one previous file is retained. Diagnostics must not interrupt control if storage is unavailable.

The diagnostic log established a real control failure on the normal charger: from 10:34 to 11:00, capacity rose from 85% to 90%, `batt_slate_mode` remained `1`, and `current_avg` stayed positive (about 339 to 770); every poll reported action `hold`. The app only wrote mode `1` at the threshold and did not reapply it when the charger resumed current. The tablet's `cm-14.1` kernel source shows each write of `1` sets slate mode, sets battery online to battery, and sends `POWER_NOW=0` to `sec-charger`; the source does not show slate mode being re-applied by this app when positive current resumes. The exact binary kernel match to the installed ROM is still unverified.

The controller now treats two consecutive positive `current_avg` samples while mode `1` and a charger is online as resumed charging. It reapplies mode `1`, verifies the readback, and retries at most once per five minutes while positive current continues. The notification reports `Charging despite pause` during that condition. The updated APK was built and installed at 11:04 on 2026-11-02. Its initial computer-USB sample was capacity 90%, mode 1, AC online 0, USB online 1, and current_avg -179. Wall-charger verification remains pending.

### Charging threshold update (2026-11-03)

The production resume threshold is now 50% and the stop threshold remains 70%. The user selected this tighter range for the wall-mounted tablet. Earlier 30% references below describe the prior configuration and diagnostic events.

### USB disconnect report clears the pause (2026-11-04)

The diagnostic log from the laptop USB session repeatedly showed the same sequence: mode 0 at 69%, `stop_threshold_write_ok` at 70%, then on the next poll `ac_online=0 usb_online=0 android_plugged=false` and `clear_on_unplug_write_ok`. This reset mode to 0 and charging resumed. The sequence repeated at 09:45, 10:36, 15:52, 17:21, and 17:48. This indicates the controller's disconnect handling was undoing its own pause; the sysfs and Android plug states cannot distinguish that from a real unplug in this case.

The controller now preserves mode 1 across offline reports and only clears the pause at 50%, including while offline. This can leave the tablet in pause mode after a real unplug until it reaches 50%; a later charger connection will remain paused until the configured resume point. The laptop USB session also showed the battery at 69% with negative `current_avg` (about -165 at the last sample), so it was not supplying net charge at that time. Wall-charger behavior should be monitored separately.

On 2026-11-03 the saved log showed a powerbank/source dropout: AC was online and average current positive at 10:18–10:19 while mode 1 was set; after the app reasserted mode 1, AC and USB both read 0 and the tablet began discharging. At 30%, the app wrote mode 0, but AC/USB remained 0 and the tablet continued down to 6%, then the service stopped with power loss. The service restarted at 12% after power returned. This fits a powerbank auto-shutdown when charging is paused or a physical disconnect; the log cannot distinguish the two.

Root cause in app logic: it treated nonzero `sec-charger/online` as connected power, even while `ac/online=0` and `usb/online=0`. The controller now uses the AC/USB online nodes, with the Android plug broadcast as fallback only when those nodes are unavailable. Follow-up logs on 2026-11-04 showed slate mode itself caused AC/USB and Android plug state to report offline, so clearing mode on every apparent disconnect made the controller resume charging right after the 70% pause. The controller now keeps slate mode latched across disconnect reports and releases it at the 50% resume threshold. This may leave the tablet paused after a genuine unplug until it reaches 50%; this is consistent with maintaining the selected hysteresis state. A bank requiring a button press/reconnect cannot be restarted by this APK.

The source-detection correction was built and installed at 15:27 on 2026-11-03. Immediately after install the device was at 12%, mode 0, AC offline, USB online, with negative `current_avg`; it was attached to the computer, whose USB output may be insufficient to charge while the tablet is on. Reconnect a reliable wall charger before leaving the tablet unattended.

## 16. USB meter measurement (2026-11-04)

The app's own readings come from the tablet's kernel, so they cannot show on their own whether the charger still delivers power while slate mode is on. To check this from outside, a RuiDeng UM34C USB meter sits between the wall charger and the tablet. The meter measures the voltage and current that the tablet actually draws from the charger.

### Meter facts (verified on 2026-11-04)

- The meter's Bluetooth address is `00:15:A3:00:36:83`, and the pairing PIN is 1234. It is paired and trusted on the user's Linux laptop.
- The meter has a physical Bluetooth switch. While the switch is off, the meter does not appear in any scan.
- The meter's USB ports carry only power. A laptop cannot read the meter over USB, so the only data link is Classic Bluetooth with the Serial Port Profile on RFCOMM channel 1.
- The meter accepts only one Bluetooth connection at a time. While the logger runs, a phone app or a second logger cannot connect.
- The laptop asks for a reading by sending the single byte `0xF0`. The meter answers with a 130-byte frame. All values in the frame are big-endian.

| Offset | Size | Value |
| --- | --- | --- |
| 0 | 2 | Header, always `0x0D4C` for the UM34C |
| 2 | 2 | Voltage in units of 0.01 V |
| 4 | 2 | Current in units of 0.001 A |
| 6 | 4 | Power in units of 0.001 W |
| 10 | 2 | Meter temperature in °C |
| 14 | 2 | Selected data group |
| 16 + 8 × group | 4 + 4 | mAh and mWh of the selected group |
| 96, 98 | 2 each | D+ and D− data-line voltages in units of 0.01 V |
| 100 | 2 | Detected charging protocol |
| 102, 106 | 4 each | mAh and mWh of the meter's own recording |
| 112 | 4 | Seconds of the meter's own recording |

The first reading was taken on 2026-11-04 at 16:52 while the tablet was charging in mode 0. It showed 5.09 V, 0.644 A and 3.28 W.

### Logging and comparison tools

`tools/um34c_logger.py` reads the meter once per second and appends each reading to a CSV file with a local timestamp. It needs only the Python standard library. After a Bluetooth dropout it reconnects by itself, and the outage shows up as a gap in the timestamps.

`tools/compare_charging.py` merges the meter CSV with `battery-guardian.log`. It gives each meter reading the app state from the most recent poll or mode write. Mode 1 counts as paused even when the AC and USB nodes report offline, because slate mode itself makes them report offline (see the 2026-11-04 entry above). The report lists the meter current for each app state, the paused periods in which the meter saw more than 0.05 A for at least 30 seconds, each cycle with its start and end capacity and the energy delivered, and the meter current before and after each mode write. The README has the commands.

One limit applies to the app's own check. The app counts positive battery current only while it sees a charger online. In slate mode the charger usually reads offline, so the app's `Charging despite pause` warning may never fire. During a pause, the meter is therefore the only reliable sign that the charger still delivers power.

The first long logging run started on 2026-11-04. Its results are still pending.

## Follow-up: resume recovery attempt (2026-11-05)

After the 2026-11-04 meter comparison showed that clearing slate mode did not reliably restart battery charging until the cable was replugged, the controller gained a one-time resume pulse. After mode 1 is cleared at or below 50%, it waits for two consecutive negative `current_avg` samples while a source is reported present, writes mode 1, waits 1.5 seconds, then writes mode 0 and verifies both writes. It checks subsequent polls for positive average current; if current remains negative after the attempt, it reports that charging was not detected and asks for a charger replug. Positive average current on two polls marks charging confirmed. The notification distinguishes “charge allowed” from confirmed charging.

The updated APK was built and installed on 2026-11-05. At installation the tablet was at 57%, mode 0, attached to laptop USB. The foreground service started successfully. Its laptop-USB readings were negative and noisy; no recovery pulse was expected or attempted because the tablet was above 50%. The new behavior has not yet been validated with the wall charger or at a resume event. Do not count laptop USB readings as wall-charger validation. Next validation: keep the tablet on the wall charger through a resume at 50%, then check the notification and diagnostic log for `resume_recovery_pulse` followed by `resume_charge_confirmed`; if it reports `resume_recovery_failed`, replug the wall-charger cable and see whether positive current is then confirmed.

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
9. While slate mode is on and the wall charger is connected, does the tablet still draw current from the charger? If it does, the charger may be powering the system while the battery is not charged, or the charge may have resumed. The UM34C meter log from section 16 should answer this. Compare the meter current with the app's `battery_status` and `current_avg` for the same periods.

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
