#!/usr/bin/env python3
"""Log readings from a RuiDeng UM34C USB meter over Bluetooth to a CSV file.

The meter sits between the wall charger and the tablet, so its current is the
current the tablet really pulls from the charger. Uses only the standard library
(Linux Bluetooth sockets). Pair the meter first, e.g. with bluetoothctl (PIN 1234).

Usage:
    tools/um34c_logger.py --address 00:15:A3:00:36:83 --output meter.csv
"""

import argparse
import csv
import os
import signal
import socket
import struct
import sys
import time
from datetime import datetime

FRAME_LENGTH = 130
REQUEST_DATA = b"\xf0"
UM34C_HEADER = 0x0D4C

FIELDS = [
    "timestamp",
    "voltage_v",
    "current_a",
    "power_w",
    "temperature_c",
    "data_plus_v",
    "data_minus_v",
    "charge_mode",
    "record_mah",
    "record_mwh",
    "record_seconds",
    "group",
    "group_mah",
    "group_mwh",
]


def timestamp():
    """Local time with offset, matching the app's diagnostic log format."""
    now = datetime.now().astimezone()
    return now.strftime("%Y-%m-%dT%H:%M:%S.") + "%03d" % (now.microsecond // 1000) + \
        now.strftime("%z")


def decode(frame):
    """Decode one 130-byte UM34C status frame into a dict of FIELDS (minus timestamp)."""
    if len(frame) != FRAME_LENGTH:
        raise ValueError("frame has %d bytes, expected %d" % (len(frame), FRAME_LENGTH))
    header = struct.unpack(">H", frame[0:2])[0]
    if header != UM34C_HEADER:
        raise ValueError("unexpected frame header 0x%04x" % header)

    def u16(offset):
        return struct.unpack(">H", frame[offset:offset + 2])[0]

    def u32(offset):
        return struct.unpack(">I", frame[offset:offset + 4])[0]

    group = u16(14)
    return {
        "voltage_v": u16(2) / 100,
        "current_a": u16(4) / 1000,
        "power_w": u32(6) / 1000,
        "temperature_c": u16(10),
        "data_plus_v": u16(96) / 100,
        "data_minus_v": u16(98) / 100,
        "charge_mode": u16(100),
        "record_mah": u32(102),
        "record_mwh": u32(106),
        "record_seconds": u32(112),
        "group": group,
        "group_mah": u32(16 + group * 8),
        "group_mwh": u32(20 + group * 8),
    }


class Meter:
    def __init__(self, address, channel, timeout):
        self.address = address
        self.channel = channel
        self.timeout = timeout
        self.sock = None

    def connect(self):
        self.close()
        sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        sock.settimeout(self.timeout)
        sock.connect((self.address, self.channel))
        self.sock = sock

    def close(self):
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None

    def read(self):
        self.sock.sendall(REQUEST_DATA)
        frame = b""
        while len(frame) < FRAME_LENGTH:
            chunk = self.sock.recv(FRAME_LENGTH - len(frame))
            if not chunk:
                raise ConnectionError("meter closed the connection")
            frame += chunk
        return decode(frame)


def log(message):
    print("%s %s" % (timestamp(), message), file=sys.stderr, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--address", "-a", required=True, help="meter Bluetooth address")
    parser.add_argument("--output", "-o", required=True,
                        help="CSV file; appended to if it already exists")
    parser.add_argument("--interval", "-i", type=float, default=1.0,
                        help="seconds between readings (default 1.0, min 0.5)")
    parser.add_argument("--channel", type=int, default=1, help="RFCOMM channel (default 1)")
    parser.add_argument("--timeout", type=float, default=10.0,
                        help="socket timeout in seconds (default 10)")
    parser.add_argument("--quiet", "-q", action="store_true",
                        help="do not echo each reading to stderr")
    args = parser.parse_args()
    interval = max(args.interval, 0.5)

    stop = {"requested": False}

    def request_stop(signum, frame):
        stop["requested"] = True

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    new_file = not os.path.exists(args.output) or os.path.getsize(args.output) == 0
    meter = Meter(args.address, args.channel, args.timeout)
    retry_delay = 2.0

    with open(args.output, "a", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        if new_file:
            writer.writeheader()
            handle.flush()

        while not stop["requested"]:
            if meter.sock is None:
                try:
                    meter.connect()
                    log("connected to %s" % args.address)
                    retry_delay = 2.0
                except OSError as error:
                    log("connect failed: %s; retrying in %.0f s" % (error, retry_delay))
                    time.sleep(retry_delay)
                    retry_delay = min(retry_delay * 2, 60.0)
                    continue

            started = time.monotonic()
            try:
                row = meter.read()
            except (OSError, ConnectionError, ValueError) as error:
                # A gap in the CSV timestamps marks the outage; no fake rows are written.
                log("read failed: %s; reconnecting" % error)
                meter.close()
                continue

            row["timestamp"] = timestamp()
            writer.writerow(row)
            handle.flush()
            if not args.quiet:
                log("%.2f V  %.3f A  %.3f W  %d C" % (
                    row["voltage_v"], row["current_a"], row["power_w"], row["temperature_c"]))

            remaining = interval - (time.monotonic() - started)
            if remaining > 0:
                time.sleep(remaining)

    meter.close()
    log("stopped")


if __name__ == "__main__":
    main()
