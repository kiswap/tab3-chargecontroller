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

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Foreground polling service. It never reports control as active without write-access verification. */
public final class BatteryService extends Service {
    private static final String TAG = "BatteryGuardian";
    private static final String CHANNEL_ID = "battery_guardian_status";
    private static final int NOTIFICATION_ID = 1;
    private static final long POLL_MS = 60_000L;
    private static final int STOP_AT = 70;
    private static final int RESUME_AT = 30;
    private static final String SLATE = "/sys/class/power_supply/battery/batt_slate_mode";
    private static final String CAPACITY = "/sys/class/power_supply/battery/capacity";
    private static final String CHARGER = "/sys/class/power_supply/sec-charger/online";

    private HandlerThread workerThread;
    private Handler worker;
    private volatile int broadcastPercent = -1;
    private volatile boolean receiverRegistered;
    private volatile int lastKnownMode = -1;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) {
                broadcastPercent = Math.max(0, Math.min(100, (level * 100) / scale));
            }
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (IOException e) {
                Log.w(TAG, "Battery control unavailable: " + e.getMessage());
                postNotice("Control unavailable", broadcastPercent);
            } catch (Exception e) {
                Log.e(TAG, "Controller poll failed", e);
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
        int chargerOnline = Integer.parseInt(readNode(CHARGER));
        if ((mode != 0 && mode != 1) || percent < 0) {
            Log.w(TAG, "Invalid battery values: capacity=" + percent + " mode=" + mode);
            updateUnavailableNotice();
            return;
        }

        // Opening read/write without changing the file distinguishes monitoring from control.
        if (!canOpenControlNode()) {
            updateUnavailableNotice();
            return;
        }

        lastKnownMode = mode;

        int nextMode = mode;
        if (chargerOnline <= 0) {
            // Clear slate mode on unplug so a later connection starts in normal mode.
            nextMode = 0;
        } else if (mode == 0 && percent >= STOP_AT) {
            nextMode = 1;
        } else if (mode == 1 && percent <= RESUME_AT) {
            nextMode = 0;
        }

        if (nextMode != mode) {
            writeMode(nextMode);
            mode = nextMode;
        }
        lastKnownMode = mode;
        String message;
        if (chargerOnline <= 0) {
            message = "Charger disconnected · " + percent + "%";
        } else if (mode == 1) {
            message = "Discharging · " + percent + "% (resumes at 30%)";
        } else {
            message = "Charging · " + percent + "% (stops at 70%)";
        }
        postNotice(message, percent);
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
