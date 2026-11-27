package com.ffbeforever.saveedit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Brings the speed button back after a phone restart, if it was on. */
public class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context c, Intent i) {
        if (!c.getSharedPreferences("editor", Context.MODE_PRIVATE).getBoolean("speedAuto", false)) return;
        if (!Settings.canDrawOverlays(c)) return;
        try { c.startForegroundService(new Intent(c, SpeedService.class)); } catch (Exception ignored) { }
    }
}
