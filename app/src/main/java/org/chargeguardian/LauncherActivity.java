package org.chargeguardian;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/** One-shot launcher entry point; the persistent notification is the interface. */
public final class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent service = new Intent(this, BatteryService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        finish();
    }
}
