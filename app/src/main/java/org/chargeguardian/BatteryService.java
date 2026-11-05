package org.chargeguardian;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.BatteryManager;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Foreground polling service. It never reports control as active without write-access verification. */
public final class BatteryService extends Service {
    private static final String TAG = "BatteryGuardian";
    private static final String CHANNEL_ID = "battery_guardian_status";
    private static final int NOTIFICATION_ID = 1;
    private static final long POLL_MS = 60_000L;
    private static final long SLATE_REASSERT_INTERVAL_MS = 5 * 60_000L;
    private static final int POSITIVE_CURRENT_SAMPLES_TO_REASSERT = 2;
    private static final int RESUME_CURRENT_SAMPLES_TO_CONFIRM = 2;
    private static final int RESUME_CURRENT_SAMPLES_TO_RESET = 2;
    private static final int RESUME_CURRENT_SAMPLES_AFTER_RESET_TO_FAIL = 2;
    private static final long RESUME_RESET_PAUSE_MS = 1_500L;
    private static final int STOP_AT = 70;
    private static final int RESUME_AT = 50;
    private static final String SLATE = "/sys/class/power_supply/battery/batt_slate_mode";
    private static final String CAPACITY = "/sys/class/power_supply/battery/capacity";
    private static final String BATTERY_STATUS = "/sys/class/power_supply/battery/status";
    private static final String CURRENT_NOW = "/sys/class/power_supply/battery/current_now";
    private static final String CURRENT_AVG = "/sys/class/power_supply/battery/current_avg";
    private static final String CHARGER = "/sys/class/power_supply/sec-charger/online";
    private static final String AC_ONLINE = "/sys/class/power_supply/ac/online";
    private static final String USB_ONLINE = "/sys/class/power_supply/usb/online";
    private static final long MAX_DIAGNOSTIC_BYTES = 512 * 1024;

    private HandlerThread workerThread;
    private Handler worker;
    private volatile int broadcastPercent = -1;
    private volatile boolean broadcastExternalPower;
    private volatile boolean receiverRegistered;
    private volatile int lastKnownMode = -1;
    private int positiveCurrentSamples;
    private long lastSlateReassertElapsedMs;
    private boolean controllerInitialized;
    private boolean resumeRecoveryPending;
    private boolean resumeRecoveryAttempted;
    private boolean resumeRecoveryFailed;
    private int chargingCurrentSamples;
    private int negativeResumeCurrentSamples;
    private int negativeCurrentSamplesAfterReset;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) {
                broadcastPercent = Math.max(0, Math.min(100, (level * 100) / scale));
            }
            broadcastExternalPower = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (IOException e) {
                Log.w(TAG, "Battery control unavailable: " + e.getMessage());
                recordDiagnostic("poll_error", "error=" + clean(e.toString()));
                postNotice("Control unavailable", broadcastPercent);
            } catch (Exception e) {
                Log.e(TAG, "Controller poll failed", e);
                recordDiagnostic("poll_error", "error=" + clean(e.toString()));
                postNotice("Control unavailable", broadcastPercent);
            } finally {
                if (worker != null) worker.postDelayed(this, POLL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        postNotice("Starting", readBatteryPercent());
        startForeground(NOTIFICATION_ID, buildNotification("Starting", readBatteryPercent()));
        Intent sticky = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        receiverRegistered = true;
        if (sticky != null) batteryReceiver.onReceive(this, sticky);
        workerThread = new HandlerThread("BatteryGuardianController");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        recordDiagnostic("service_start", "pid=" + android.os.Process.myPid());
        worker.post(poll);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (workerThread != null) workerThread.quitSafely();
        if (receiverRegistered) unregisterReceiver(batteryReceiver);
        recordDiagnostic("service_stop", "last_mode=" + lastKnownMode);
        // A graceful stop should not leave charging paused. This cannot cover a hard process kill.
        if (lastKnownMode == 1) {
            try {
                writeMode(0);
            } catch (Exception e) {
                Log.e(TAG, "Could not restore charging on service stop", e);
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void tick() throws Exception {
        int percent = boundedPercent(Integer.parseInt(readNode(CAPACITY)));
        int mode = Integer.parseInt(readNode(SLATE));
        int secChargerOnline = Integer.parseInt(readNode(CHARGER));
        String batteryStatus = optionalNode(BATTERY_STATUS);
        String currentNow = optionalNode(CURRENT_NOW);
        String currentAvg = optionalNode(CURRENT_AVG);
        String acOnline = optionalNode(AC_ONLINE);
        String usbOnline = optionalNode(USB_ONLINE);
        int acOnlineValue = parseIntOrUnavailable(acOnline);
        int usbOnlineValue = parseIntOrUnavailable(usbOnline);
        boolean sysfsPowerStateAvailable = acOnlineValue != Integer.MIN_VALUE
                || usbOnlineValue != Integer.MIN_VALUE;
        boolean chargerConnected = acOnlineValue > 0 || usbOnlineValue > 0;
        if (!sysfsPowerStateAvailable) chargerConnected = broadcastExternalPower;
        if ((mode != 0 && mode != 1) || percent < 0) {
            Log.w(TAG, "Invalid battery values: capacity=" + percent + " mode=" + mode);
            recordDiagnostic("invalid_state", "capacity=" + percent + " mode=" + mode
                    + " sec_charger_online=" + secChargerOnline
                    + " external_power_online=" + chargerConnected
                    + " android_plugged=" + broadcastExternalPower
                    + " battery_status=" + batteryStatus
                    + " current_now=" + currentNow + " current_avg=" + currentAvg
                    + " ac_online=" + acOnline + " usb_online=" + usbOnline);
            updateUnavailableNotice();
            return;
        }

        // Opening read/write without changing the file distinguishes monitoring from control.
        if (!canOpenControlNode()) {
            recordDiagnostic("control_unavailable", "capacity=" + percent + " mode=" + mode
                    + " sec_charger_online=" + secChargerOnline
                    + " external_power_online=" + chargerConnected
                    + " android_plugged=" + broadcastExternalPower
                    + " battery_status=" + batteryStatus
                    + " current_now=" + currentNow + " current_avg=" + currentAvg
                    + " ac_online=" + acOnline + " usb_online=" + usbOnline);
            updateUnavailableNotice();
            return;
        }

        if (!controllerInitialized) {
            controllerInitialized = true;
            // A restarted service may find the tablet already below the resume point.
            if (mode == 0 && percent <= RESUME_AT) resumeRecoveryPending = true;
        }

        lastKnownMode = mode;

        int nextMode = mode;
        String action = "hold";
        if (mode == 0 && chargerConnected && percent >= STOP_AT) {
            nextMode = 1;
            action = "stop_threshold";
        } else if (mode == 1 && percent <= RESUME_AT) {
            // Keep a pause latched across apparent disconnects. This kernel can report
            // AC/USB offline as a consequence of slate mode itself, especially on USB.
            nextMode = 0;
            action = "resume_threshold";
        }

        if (nextMode != mode) {
            writeMode(nextMode);
            mode = nextMode;
            action += "_write_ok";
            if (mode == 0 && percent <= RESUME_AT) {
                resumeRecoveryPending = true;
                resumeRecoveryAttempted = false;
                resumeRecoveryFailed = false;
                chargingCurrentSamples = 0;
                negativeResumeCurrentSamples = 0;
                negativeCurrentSamplesAfterReset = 0;
            } else if (mode == 1) {
                clearResumeRecoveryState();
            }
        }

        long averageCurrent = parseLongOrUnavailable(currentAvg);
        boolean chargeCurrentObserved = chargerConnected && mode == 1 && averageCurrent > 0;
        if (chargeCurrentObserved) {
            positiveCurrentSamples++;
            long now = android.os.SystemClock.elapsedRealtime();
            if (positiveCurrentSamples >= POSITIVE_CURRENT_SAMPLES_TO_REASSERT
                    && (lastSlateReassertElapsedMs == 0
                    || now - lastSlateReassertElapsedMs >= SLATE_REASSERT_INTERVAL_MS)) {
                // A cable/source change can restart charging in this kernel without clearing
                // batt_slate_mode. Reapplying 1 repeats the kernel's actual charger-off action.
                writeModeAndVerify(1);
                lastSlateReassertElapsedMs = now;
                action = "reassert_slate_mode_positive_current";
            } else if (positiveCurrentSamples >= POSITIVE_CURRENT_SAMPLES_TO_REASSERT) {
                action = "positive_current_after_pause";
            } else {
                action = "confirm_positive_current";
            }
        } else {
            positiveCurrentSamples = 0;
            lastSlateReassertElapsedMs = 0;
        }

        String resumeAction = updateResumeRecovery(percent, mode, chargerConnected, averageCurrent);
        if (resumeAction != null) action = resumeAction;

        lastKnownMode = mode;
        recordDiagnostic("poll", "capacity=" + percent + " mode=" + mode
                + " sec_charger_online=" + secChargerOnline
                + " external_power_online=" + chargerConnected
                + " android_plugged=" + broadcastExternalPower
                + " battery_status=" + batteryStatus
                + " current_now=" + currentNow + " current_avg=" + currentAvg
                + " ac_online=" + acOnline + " usb_online=" + usbOnline
                + " positive_current_samples=" + positiveCurrentSamples
                + " resume_recovery_pending=" + resumeRecoveryPending
                + " resume_recovery_attempted=" + resumeRecoveryAttempted
                + " resume_recovery_failed=" + resumeRecoveryFailed
                + " charging_current_samples=" + chargingCurrentSamples
                + " action=" + action + " readback=" + optionalNode(SLATE));
        String message;
        if (!chargerConnected) {
            message = "Charger disconnected · " + percent + "%";
        } else if (mode == 1) {
            if (positiveCurrentSamples >= POSITIVE_CURRENT_SAMPLES_TO_REASSERT) {
                message = "Charging despite pause · " + percent + "%";
            } else if (positiveCurrentSamples > 0) {
                message = "Checking charge pause · " + percent + "%";
            } else {
                message = "Discharging · " + percent + "% (resumes at 50%)";
            }
        } else {
            if (resumeRecoveryFailed) {
                message = "Charging not detected · replug charger · " + percent + "%";
            } else if (resumeRecoveryPending && resumeRecoveryAttempted) {
                message = "Charge reset tried · checking · " + percent + "%";
            } else if (resumeRecoveryPending) {
                message = "Checking charge resume · " + percent + "%";
            } else if (chargingCurrentSamples >= RESUME_CURRENT_SAMPLES_TO_CONFIRM) {
                message = "Charging confirmed · " + percent + "% (stops at 70%)";
            } else {
                message = "Charge allowed · " + percent + "% (stops at 70%)";
            }
        }
        postNotice(message, percent);
    }

    private String updateResumeRecovery(int percent, int mode, boolean chargerConnected,
                                        long averageCurrent) throws IOException {
        if (mode != 0) {
            chargingCurrentSamples = 0;
            return null;
        }
        if (percent >= STOP_AT) {
            clearResumeRecoveryState();
            return null;
        }
        if (!chargerConnected || averageCurrent == Long.MIN_VALUE) {
            chargingCurrentSamples = 0;
            negativeResumeCurrentSamples = 0;
            negativeCurrentSamplesAfterReset = 0;
            return null;
        }

        if (averageCurrent > 0) {
            chargingCurrentSamples++;
            negativeResumeCurrentSamples = 0;
            negativeCurrentSamplesAfterReset = 0;
            if (chargingCurrentSamples >= RESUME_CURRENT_SAMPLES_TO_CONFIRM) {
                if (resumeRecoveryPending) {
                    resumeRecoveryPending = false;
                    resumeRecoveryAttempted = false;
                    resumeRecoveryFailed = false;
                    return "resume_charge_confirmed";
                }
                if (resumeRecoveryFailed) {
                    resumeRecoveryFailed = false;
                    return "charging_confirmed_after_replug";
                }
            }
            return null;
        }

        if (averageCurrent < 0) {
            chargingCurrentSamples = 0;
            if (resumeRecoveryPending && !resumeRecoveryAttempted) {
                negativeResumeCurrentSamples++;
                if (negativeResumeCurrentSamples >= RESUME_CURRENT_SAMPLES_TO_RESET) {
                    pulseChargingMode();
                    resumeRecoveryAttempted = true;
                    negativeResumeCurrentSamples = 0;
                    negativeCurrentSamplesAfterReset = 0;
                    return "resume_recovery_pulse";
                }
            } else if (resumeRecoveryPending && resumeRecoveryAttempted) {
                negativeCurrentSamplesAfterReset++;
                if (negativeCurrentSamplesAfterReset >= RESUME_CURRENT_SAMPLES_AFTER_RESET_TO_FAIL) {
                    resumeRecoveryPending = false;
                    resumeRecoveryFailed = true;
                    return "resume_recovery_failed";
                }
            }
        }
        return null;
    }

    private void pulseChargingMode() throws IOException {
        recordDiagnostic("resume_recovery_start", "pause_ms=" + RESUME_RESET_PAUSE_MS);
        try {
            writeModeAndVerify(1);
            android.os.SystemClock.sleep(RESUME_RESET_PAUSE_MS);
            writeModeAndVerify(0);
        } catch (IOException e) {
            // Never intentionally leave the battery paused after a failed reset attempt.
            try {
                writeModeAndVerify(0);
            } catch (IOException recoveryError) {
                Log.e(TAG, "Could not restore charging after resume reset", recoveryError);
            }
            throw e;
        }
    }

    private void clearResumeRecoveryState() {
        resumeRecoveryPending = false;
        resumeRecoveryAttempted = false;
        resumeRecoveryFailed = false;
        chargingCurrentSamples = 0;
        negativeResumeCurrentSamples = 0;
        negativeCurrentSamplesAfterReset = 0;
    }

    private void writeMode(int mode) throws Exception {
        if (mode != 0 && mode != 1) throw new IllegalArgumentException("Invalid slate mode");
        try {
            writeModeAndVerify(mode);
        } catch (Exception e) {
            if (mode == 1) {
                try {
                    writeModeAndVerify(0);
                } catch (Exception recoveryError) {
                    Log.e(TAG, "Best-effort charging recovery failed", recoveryError);
                }
            }
            throw e;
        }
    }

    private void writeModeAndVerify(int mode) throws IOException {
        try (FileOutputStream output = new FileOutputStream(SLATE)) {
            output.write((Integer.toString(mode) + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        String actual = readNode(SLATE);
        if (!Integer.toString(mode).equals(actual)) {
            throw new IOException("Slate mode readback mismatch: expected " + mode + ", got " + actual);
        }
        recordDiagnostic("write_verified", "requested_mode=" + mode + " readback=" + actual);
    }

    private String optionalNode(String path) {
        try {
            return readNode(path).replaceAll("\\s+", "_");
        } catch (Exception e) {
            return "unavailable:" + e.getClass().getSimpleName();
        }
    }

    private static long parseLongOrUnavailable(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private static int parseIntOrUnavailable(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    /** Keep a short, ADB-readable rolling history; diagnostics must never interrupt control. */
    private void recordDiagnostic(String event, String details) {
        String line = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                .format(new Date()) + " event=" + event + " " + details;
        Log.i(TAG, "DIAG " + line);
        try {
            File directory = getExternalFilesDir("diagnostics");
            if (directory == null) return;
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Cannot create diagnostics directory");
            }
            File logFile = new File(directory, "battery-guardian.log");
            File previous = new File(directory, "battery-guardian.log.1");
            if (logFile.length() >= MAX_DIAGNOSTIC_BYTES) {
                if (previous.exists() && !previous.delete()) {
                    throw new IOException("Cannot rotate previous diagnostic log");
                }
                if (!logFile.renameTo(previous)) {
                    throw new IOException("Cannot rotate diagnostic log");
                }
            }
            try (FileOutputStream output = new FileOutputStream(logFile, true)) {
                output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not persist diagnostics: " + e.getMessage());
        }
    }

    private static String clean(String value) {
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private String readNode(String path) throws IOException {
        byte[] buffer = new byte[64];
        try (FileInputStream input = new FileInputStream(path)) {
            int count = input.read(buffer);
            if (count <= 0) throw new IOException("Empty sysfs value: " + path);
            return new String(buffer, 0, count, StandardCharsets.US_ASCII).trim();
        }
    }

    private boolean canOpenControlNode() {
        java.io.FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(SLATE, OsConstants.O_WRONLY, 0);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Control node is not writable by this app: " + e.getMessage());
            return false;
        } finally {
            if (descriptor != null) {
                try {
                    Os.close(descriptor);
                } catch (Exception e) {
                    Log.w(TAG, "Could not close control node", e);
                }
            }
        }
    }

    private void updateUnavailableNotice() {
        postNotice("Control unavailable", broadcastPercent);
    }

    private void postNotice(String text, int percent) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text, percent));
    }

    private Notification buildNotification(String text, int percent) {
        String display = text;
        if (percent >= 0 && !text.contains(percent + "%")) display += " · " + percent + "%";
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        int iconId = getResources().getIdentifier("ic_battery_guardian", "drawable", getPackageName());
        return builder.setSmallIcon(iconId)
                .setContentTitle("Battery Guardian")
                .setContentText(display)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Battery Guardian status", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Charging control status");
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private int readBatteryPercent() {
        return broadcastPercent;
    }

    private static int boundedPercent(int value) {
        return value < 0 || value > 100 ? -1 : value;
    }

}
