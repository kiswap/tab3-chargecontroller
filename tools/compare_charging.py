#!/usr/bin/env python3
"""Compare UM34C meter readings with the Battery Guardian diagnostic log.

The meter CSV comes from tools/um34c_logger.py. The app log is
battery-guardian.log (and optionally battery-guardian.log.1) from
/sdcard/Android/data/org.chargeguardian/files/diagnostics/ on the tablet.

The report answers two questions:
  * While the app says the charge is paused ("Discharging"), does the tablet
    still pull current from the charger?
  * Do the charge cycles start and stop at the planned battery levels?

Usage:
    tools/compare_charging.py meter.csv battery-guardian.log.1 battery-guardian.log
    tools/compare_charging.py meter.csv --adb            # pull the logs over adb
    tools/compare_charging.py meter.csv app.log --merged merged.csv
"""

import argparse
import csv
import os
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta

DEVICE_LOG_DIR = "/sdcard/Android/data/org.chargeguardian/files/diagnostics"
DEVICE_LOGS = ["battery-guardian.log.1", "battery-guardian.log"]
TIME_FORMAT = "%Y-%m-%dT%H:%M:%S.%f%z"

STATE_CHARGING = "charging"            # mode 0, charger reported online
STATE_PAUSED = "paused"                # mode 1, no positive battery current seen
STATE_PAUSED_ALERT = "paused_alert"    # mode 1, app saw positive battery current
STATE_UNPLUGGED = "unplugged"          # mode 0, no external power reported
STATE_UNKNOWN = "unknown"              # no recent app log entry
STATE_ORDER = [STATE_CHARGING, STATE_PAUSED, STATE_PAUSED_ALERT, STATE_UNPLUGGED, STATE_UNKNOWN]

STATE_LABEL = {
    STATE_CHARGING: "Charging (mode 0)",
    STATE_PAUSED: "Paused (mode 1)",
    STATE_PAUSED_ALERT: "Paused, app saw charge current",
    STATE_UNPLUGGED: "Mode 0, charger offline",
    STATE_UNKNOWN: "No app log coverage",
}

LINE_PATTERN = re.compile(r"^(\S+) event=(\S+)\s*(.*)$")


def parse_time(text):
    return datetime.strptime(text, TIME_FORMAT)


def parse_app_logs(paths, offset_seconds):
    """Return app events sorted by time; each has time, event and a dict of fields."""
    events = []
    shift = timedelta(seconds=offset_seconds)
    for path in paths:
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                match = LINE_PATTERN.match(line.strip())
                if not match:
                    continue
                try:
                    when = parse_time(match.group(1)) + shift
                except ValueError:
                    continue
                fields = dict(part.split("=", 1) for part in match.group(3).split() if "=" in part)
                events.append({"time": when, "event": match.group(2), "fields": fields})
    events.sort(key=lambda item: item["time"])
    return events


def state_of_poll(fields):
    # Slate mode itself makes the AC/USB nodes read offline, so mode 1 decides
    # before the offline flag. Only mode 0 with no input counts as unplugged.
    mode = fields.get("mode")
    if mode == "1":
        if int(fields.get("positive_current_samples", "0") or 0) > 0:
            return STATE_PAUSED_ALERT
        return STATE_PAUSED
    if mode == "0":
        if fields.get("external_power_online") == "false":
            return STATE_UNPLUGGED
        return STATE_CHARGING
    return STATE_UNKNOWN


def build_timeline(events):
    """Turn app events into (time, state, poll fields) change points.

    Polls give the full state. A verified mode write is applied at its own time,
    because the next poll can be up to a minute later.
    """
    points = []
    last_fields = {}
    for item in events:
        if item["event"] == "poll":
            last_fields = item["fields"]
            points.append((item["time"], state_of_poll(item["fields"]), last_fields))
        elif item["event"] == "write_verified":
            mode = item["fields"].get("readback")
            state = STATE_CHARGING if mode == "0" else STATE_PAUSED if mode == "1" else None
            if state is not None:
                points.append((item["time"], state, last_fields))
        elif item["event"] == "service_stop":
            points.append((item["time"], STATE_UNKNOWN, {}))
    return points


def read_meter(path):
    samples = []
    with open(path, newline="") as handle:
        for row in csv.DictReader(handle):
            try:
                samples.append({
                    "time": parse_time(row["timestamp"]),
                    "voltage_v": float(row["voltage_v"]),
                    "current_a": float(row["current_a"]),
                    "power_w": float(row["power_w"]),
                    "row": row,
                })
            except (KeyError, ValueError):
                continue
    samples.sort(key=lambda item: item["time"])
    return samples


def assign_states(samples, points, max_age):
    """Give each meter sample the app state that was valid at its time."""
    index = -1
    for sample in samples:
        while index + 1 < len(points) and points[index + 1][0] <= sample["time"]:
            index += 1
        if index < 0 or sample["time"] - points[index][0] > max_age:
            sample["state"] = STATE_UNKNOWN
            sample["app"] = {}
        else:
            sample["state"] = points[index][1]
            sample["app"] = points[index][2]


def sample_seconds(samples, i, max_gap):
    """Time this sample represents: until the next sample, capped at max_gap."""
    if i + 1 >= len(samples):
        return 1.0
    gap = (samples[i + 1]["time"] - samples[i]["time"]).total_seconds()
    return min(max(gap, 0.0), max_gap)


def runs(samples, key):
    """Split samples into consecutive runs where key(sample) is constant."""
    result = []
    for i, sample in enumerate(samples):
        value = key(sample)
        if result and result[-1]["value"] == value:
            result[-1]["indices"].append(i)
        else:
            result.append({"value": value, "indices": [i]})
    return result


def summarize(samples, indices, max_gap):
    seconds = sum(sample_seconds(samples, i, max_gap) for i in indices)
    energy_wh = sum(samples[i]["power_w"] * sample_seconds(samples, i, max_gap)
                    for i in indices) / 3600
    currents = [samples[i]["current_a"] for i in indices]
    return {
        "start": samples[indices[0]]["time"],
        "end": samples[indices[-1]]["time"],
        "seconds": seconds,
        "energy_wh": energy_wh,
        "mean_a": sum(currents) / len(currents),
        "max_a": max(currents),
        "min_a": min(currents),
    }


def fmt_time(value):
    return value.strftime("%Y-%m-%d %H:%M:%S")


def fmt_duration(seconds):
    seconds = int(round(seconds))
    hours, rest = divmod(seconds, 3600)
    minutes, secs = divmod(rest, 60)
    if hours:
        return "%dh%02dm" % (hours, minutes)
    if minutes:
        return "%dm%02ds" % (minutes, secs)
    return "%ds" % secs


def app_value(sample, key):
    return sample["app"].get(key, "?")


def pull_logs_over_adb(directory):
    paths = []
    for name in DEVICE_LOGS:
        target = os.path.join(directory, name)
        result = subprocess.run(["adb", "pull", "%s/%s" % (DEVICE_LOG_DIR, name), target],
                                capture_output=True, text=True)
        if result.returncode == 0:
            paths.append(target)
        elif name == "battery-guardian.log":
            sys.exit("adb pull failed for %s: %s" % (name, result.stderr.strip()))
    return paths


def report(samples, points, args):
    max_gap = args.max_gap
    threshold = args.threshold

    print("Meter samples: %d, from %s to %s" % (
        len(samples), fmt_time(samples[0]["time"]), fmt_time(samples[-1]["time"])))
    if points:
        print("App log entries used: %d, from %s to %s" % (
            len(points), fmt_time(points[0][0]), fmt_time(points[-1][0])))
    else:
        print("App log entries used: 0. Every meter sample is reported as 'no app log coverage'.")
    print("Current threshold for 'pulling power': %.3f A" % threshold)

    gaps = [(samples[i]["time"], samples[i + 1]["time"]) for i in range(len(samples) - 1)
            if (samples[i + 1]["time"] - samples[i]["time"]).total_seconds() > max_gap]
    if gaps:
        print("Meter data gaps longer than %ds: %d (largest %s)" % (
            max_gap, len(gaps), fmt_duration(max((b - a).total_seconds() for a, b in gaps))))

    print("\n== Meter readings per app state ==")
    print("%-32s %9s %8s %8s %9s %10s" % (
        "App state", "Time", "Mean A", "Max A", "Energy Wh", "Above thr."))
    for state in STATE_ORDER:
        indices = [i for i, s in enumerate(samples) if s["state"] == state]
        if not indices:
            continue
        info = summarize(samples, indices, max_gap)
        above = sum(1 for i in indices if samples[i]["current_a"] > threshold)
        print("%-32s %9s %8.3f %8.3f %9.3f %9.0f%%" % (
            STATE_LABEL[state], fmt_duration(info["seconds"]), info["mean_a"],
            info["max_a"], info["energy_wh"], 100.0 * above / len(indices)))

    print("\n== Paused periods where the meter still saw current above the threshold ==")
    print("(Only stretches of at least %ds are listed.)" % args.min_duration)
    paused = {STATE_PAUSED, STATE_PAUSED_ALERT}
    found = 0
    for run in runs(samples, lambda s: s["state"] in paused and s["current_a"] > threshold):
        if not run["value"]:
            continue
        info = summarize(samples, run["indices"], max_gap)
        if info["seconds"] < args.min_duration:
            continue
        found += 1
        first = samples[run["indices"][0]]
        print("%s to %s (%s): mean %.3f A, max %.3f A, %.3f Wh; app: capacity=%s%% "
              "battery_status=%s current_avg=%s" % (
                  fmt_time(info["start"]), fmt_time(info["end"]), fmt_duration(info["seconds"]),
                  info["mean_a"], info["max_a"], info["energy_wh"],
                  app_value(first, "capacity"), app_value(first, "battery_status"),
                  app_value(first, "current_avg")))
    if not found:
        print("None.")

    print("\n== App charge cycles with meter energy ==")
    print("A cycle is a stretch with one app state. Capacity is taken from the app's polls.")
    print("%-19s %-19s %9s %-32s %9s %9s %8s" % (
        "Start", "End", "Length", "App state", "Capacity", "Energy Wh", "Mean A"))
    for run in runs(samples, lambda s: s["state"]):
        info = summarize(samples, run["indices"], max_gap)
        if info["seconds"] < args.min_duration:
            continue
        first = samples[run["indices"][0]]
        last = samples[run["indices"][-1]]
        capacity = "%s->%s" % (app_value(first, "capacity"), app_value(last, "capacity"))
        print("%-19s %-19s %9s %-32s %9s %9.3f %8.3f" % (
            fmt_time(info["start"]), fmt_time(info["end"]), fmt_duration(info["seconds"]),
            STATE_LABEL[run["value"]], capacity, info["energy_wh"], info["mean_a"]))

    print("\n== Meter current around each mode write by the app ==")
    print("Before: mean of the %ds before the write. After: mean from %ds to %ds after it." % (
        args.window, args.settle, args.settle + args.window))
    times = [s["time"] for s in samples]
    shown = 0
    for item in args.events:
        if item["event"] != "write_verified":
            continue
        when = item["time"]
        before = [s["current_a"] for s, t in zip(samples, times)
                  if when - timedelta(seconds=args.window) <= t < when]
        after = [s["current_a"] for s, t in zip(samples, times)
                 if when + timedelta(seconds=args.settle) <= t
                 < when + timedelta(seconds=args.settle + args.window)]
        if not before and not after:
            continue
        shown += 1
        mean = lambda values: "%.3f A" % (sum(values) / len(values)) if values else "no data"
        print("%s mode=%s: before %s, after %s" % (
            fmt_time(when), item["fields"].get("readback", "?"), mean(before), mean(after)))
    if not shown:
        print("No mode writes inside the meter's time range.")


def write_merged(samples, path):
    app_keys = ["capacity", "mode", "battery_status", "current_now", "current_avg",
                "external_power_online", "positive_current_samples", "action"]
    meter_keys = list(samples[0]["row"].keys())
    with open(path, "w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(meter_keys + ["app_state"] + ["app_" + key for key in app_keys])
        for sample in samples:
            writer.writerow([sample["row"][key] for key in meter_keys] + [sample["state"]]
                            + [sample["app"].get(key, "") for key in app_keys])


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("meter_csv", help="CSV written by tools/um34c_logger.py")
    parser.add_argument("app_logs", nargs="*",
                        help="battery-guardian.log files, oldest first")
    parser.add_argument("--adb", action="store_true",
                        help="pull the app logs from the tablet with adb")
    parser.add_argument("--threshold", type=float, default=0.05,
                        help="current in A above which the tablet counts as pulling power "
                             "(default 0.05)")
    parser.add_argument("--min-duration", type=float, default=30,
                        help="ignore stretches shorter than this many seconds (default 30)")
    parser.add_argument("--max-age", type=float, default=150,
                        help="app state counts as unknown when the last app entry is older "
                             "than this many seconds (default 150; the app polls every 60)")
    parser.add_argument("--max-gap", type=float, default=10,
                        help="a meter sample stands for at most this many seconds (default 10)")
    parser.add_argument("--offset", type=float, default=0,
                        help="seconds to add to app timestamps if the tablet clock is off")
    parser.add_argument("--window", type=int, default=30,
                        help="seconds averaged before and after each mode write (default 30)")
    parser.add_argument("--settle", type=int, default=10,
                        help="seconds to skip after a mode write before averaging (default 10)")
    parser.add_argument("--merged", help="also write every meter sample with its app state "
                                         "to this CSV file")
    args = parser.parse_args()

    if args.adb and args.app_logs:
        parser.error("give app log files or --adb, not both")

    with tempfile.TemporaryDirectory() as directory:
        paths = pull_logs_over_adb(directory) if args.adb else args.app_logs
        args.events = parse_app_logs(paths, args.offset)

    samples = read_meter(args.meter_csv)
    if not samples:
        sys.exit("no readable samples in %s" % args.meter_csv)
    points = build_timeline(args.events)
    assign_states(samples, points, timedelta(seconds=args.max_age))
    report(samples, points, args)
    if args.merged:
        write_merged(samples, args.merged)
        print("\nMerged CSV written to %s" % args.merged)


if __name__ == "__main__":
    main()
