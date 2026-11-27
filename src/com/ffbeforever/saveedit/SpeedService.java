package com.ffbeforever.saveedit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Floating speed button drawn over the game. Needs "display over other apps". */
public class SpeedService extends Service {
    static final String STOP = "com.ffbeforever.saveedit.STOP";
    static boolean running;
    /** Why the last start failed, or null. Read by the app to show a clear message. */
    static volatile String lastError;

    private WindowManager wm;
    private LinearLayout root;
    private LinearLayout panel;
    private TextView bubble;
    private Button quick;
    private boolean wasGame;
    private Button autoBtn, resBtn, repBtn, cloudBtn, editBtn, botBtn, logBtn;
    private boolean autoOn, resOn, repOn, cloudOn, botOn;
    private boolean cloudHere;
    private int pollN;
    private boolean headless;
    private static volatile SpeedService inst;
    /** Called when the Gambit rules were edited: send them to the game now instead of waiting for the next poll. */
    static void poke() {
        final SpeedService s = inst;
        if (s != null) s.ui.post(new Runnable() { public void run() { s.gamSync(); } });
    }
    /** Same, but resends even if nothing changed (the "Send to game" button). Returns false when the helper is not running. */
    static boolean pokeForce() {
        final SpeedService s = inst;
        if (s == null) return false;
        s.ui.post(new Runnable() { public void run() { s.lastGam = -1; s.gamSync(); } });
        return true;
    }
    private long lastGam = -1;                  // modified time of the presets file last sent to the game
    private int lastReq = -1, lastCs = -1;   // requests from the in-game panel; last cloud state told to the game
    private String shownKey = "";
    private final java.util.concurrent.atomic.AtomicBoolean syncBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private View conflict;
    private WindowManager.LayoutParams qlp;
    private boolean quickAdded;
    /** True while the editor app itself is on screen: Quick must never show over it. */
    static volatile boolean editorFront = false;
    private final Runnable quickExpire = new Runnable() {
        public void run() { if (quick != null) quick.setVisibility(View.GONE); }
    };
    private WindowManager.LayoutParams lp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private double current = 1;

    public IBinder onBind(Intent i) { return null; }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    static List<Double> speeds(Context c) {
        SharedPreferences p = c.getSharedPreferences("editor", MODE_PRIVATE);
        String txt = p.getString("speeds", "1,2,3,5,10");
        List<Double> out = new ArrayList<Double>();
        for (String part : txt.split(",")) {
            try {
                double v = Double.parseDouble(part.trim());
                if (v >= 0.1 && v <= 20 && !out.contains(v)) out.add(v);
            } catch (NumberFormatException ignored) { }
        }
        if (!out.contains(1.0)) out.add(0, 1.0);
        return out;
    }

    public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopSelf();
            getSharedPreferences("editor", MODE_PRIVATE).edit().putBoolean("speedAuto", false).apply();
            return START_NOT_STICKY;
        }
        lastError = null;
        inst = this;
        try {
            // Android 14+ needs the service type at start; it matches foregroundServiceType="specialUse" in the manifest.
            if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(1, note(), 0x40000000 /* FOREGROUND_SERVICE_TYPE_SPECIAL_USE */);
            else startForeground(1, note());
        } catch (Exception e) {
            // Keep going: the overlay can still show without the foreground notification (Android may stop it later).
            lastError = "Android refused the speed button's notification service: " + e;
        }
        // The old floating speed bubble is switched off for good (the in-game speed button and the moogle menu replaced it). The code below stays for now; the helper mode keeps running.
        boolean overlay = false;
        if (!overlay) {
            // helper mode: no floating button and no overlay permission needed. The app still talks to the game: moogle panel
            // hand-offs (Cloud sync, Edit save, Export log), Gambit rules, auto cloud sync.
            headless = true;
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);   // the cloud sync Yes/No box is an overlay window and needs it
            if (!running) ui.postDelayed(poll, 1500);
            running = true;
            return START_STICKY;
        }
        headless = false;
        try {
            if (root == null) show();
            running = true;
        } catch (Exception e) {
            lastError = "Android refused to draw the speed button over other apps: " + e
                    + "\nCheck \"Display over other apps\" for this app. Some phones (Xiaomi, Oppo, Vivo) also need "
                    + "\"Display pop-up windows while running in the background\" or \"Floating window\" in the app's settings.";
            Toast.makeText(this, lastError, Toast.LENGTH_LONG).show();
            root = null;
            stopSelf();
        }
        return START_STICKY;     // keep alive so the button is ready when the game starts
    }

    private Notification note() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("speed", "Speed button", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, SpeedService.class).setAction(STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
        boolean hl = true;   // helper only (the floating bubble is switched off)
        if (hl) pi = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "speed")
                .setSmallIcon(android.R.drawable.ic_media_ff)
                .setContentTitle(hl ? "Kupo Forge helper is running" : "FFBE speed button is on")
                .setContentText(hl ? "Passes cloud sync, Edit save, Gambit rules and the log between the game and the app" : "Tap to hide the button")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private GradientDrawable round(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(24));
        g.setStroke(Math.max(1, dp(1.5f)), 0xffd4b46a);   // gold edge, matches the app theme
        return g;
    }

    private void show() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);

        bubble = new TextView(this);
        bubble.setText("1x");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(13);
        bubble.setTypeface(null, android.graphics.Typeface.BOLD);
        bubble.setGravity(Gravity.BOTTOM | Gravity.END);
        bubble.setShadowLayer(4f, 0f, 0f, 0xFF000000);
        bubble.setPadding(0, 0, dp(2), 0);
        try {   // the Kupo Forge mascot, transparent background; the state shows in the speed text colour
            java.io.InputStream mi = getAssets().open("mascot_float.png");
            android.graphics.Bitmap mb = android.graphics.BitmapFactory.decodeStream(mi);
            mi.close();
            bubble.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), mb));
        } catch (Exception e) { bubble.setBackground(round(0xE612204a)); }
        root.addView(bubble, new LinearLayout.LayoutParams(dp(56), dp(56)));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        panel.setBackground(round(0xE6070d24));
        panel.setPadding(dp(4), dp(2), dp(4), dp(2));
        panel.setVisibility(View.GONE);
        for (final Double v : speeds(this)) {
            Button b = new Button(this);
            b.setText(Speed.label(v));
            b.setTextSize(13);
            b.setAllCaps(false);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(8), 0, dp(8), 0);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) { set(v); }
            });
            speedRow.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)));
        }
        Button close = new Button(this);
        close.setText("✕");
        close.setTextSize(13);
        close.setAllCaps(false);
        close.setMinWidth(0);
        close.setMinimumWidth(0);
        close.setPadding(dp(8), 0, dp(8), 0);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { panel.setVisibility(View.GONE); }   // back to the small round button
        });
        speedRow.addView(close, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)));
        panel.addView(speedRow);
        root.addView(panel);
        resBtn = toggle("Skip results", new View.OnClickListener() {
            public void onClick(View view) { send("res " + (resOn ? 0 : 1), false); }
        });
        autoBtn = toggle("Auto battle", new View.OnClickListener() {
            public void onClick(View view) { send("auto " + (autoOn ? 0 : 1), false); }
        });
        repBtn = toggle("Repeat battle", new View.OnClickListener() {
            public void onClick(View view) { send("rep " + (repOn ? 0 : 1), false); }
        });
        panel.addView(resBtn);
        panel.addView(autoBtn);
        cloudBtn = toggle("Cloud sync", new View.OnClickListener() {
            public void onClick(View view) { 
                getSharedPreferences("editor", MODE_PRIVATE).edit().putBoolean("syncAuto", !cloudOn).apply();
                cloudOn = !cloudOn;
                cloudBtn.setText("Cloud sync: " + (cloudOn ? "ON" : "OFF"));
                cloudBtn.setBackground(round(cloudOn ? 0xCC1B7F3B : 0xCC444444));
                if (cloudOn) syncTick();
            }
        });
        panel.addView(repBtn);
        panel.addView(cloudBtn);
        editBtn = toggle("Open Kupo Forge", new View.OnClickListener() {
            public void onClick(View view) {
                panel.setVisibility(View.GONE);
                try {
                    Intent li = new Intent(SpeedService.this, MainActivity.class);
                    li.putExtra("live", true);
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(li);
                } catch (Exception e) {
                    Toast.makeText(SpeedService.this, "Could not open the editor: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        editBtn.setBackground(round(0xCC12204a));
        panel.addView(editBtn);
        logBtn = toggle("Export Kupo log", new View.OnClickListener() {
            public void onClick(View view) { panel.setVisibility(View.GONE); exportLog(); }
        });
        logBtn.setBackground(round(0xCC12204a));
        panel.addView(logBtn);
        botBtn = toggle("Gambit", new View.OnClickListener() {
            public void onClick(View view) { send("bot " + (botOn ? 0 : 1), false); }
        });
        panel.addView(botBtn, panel.indexOfChild(cloudBtn));   // Gambit sits above Cloud sync
        root.setVisibility(View.GONE);      // shown only while the game answers and is on screen

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        SharedPreferences p = getSharedPreferences("editor", MODE_PRIVATE);
        lp.x = p.getInt("bx", dp(8));
        lp.y = p.getInt("by", dp(120));

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy; boolean moved;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = lp.x; oy = lp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                        if (moved) {
                            lp.x = ox + (int) dx;
                            lp.y = oy + (int) dy;
                            wm.updateViewLayout(root, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            getSharedPreferences("editor", MODE_PRIVATE).edit()
                                    .putInt("bx", lp.x).putInt("by", lp.y).apply();
                        } else {
                            panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                            if (panel.getVisibility() == View.VISIBLE) ask();
                        }
                        return true;
                }
                return false;
            }
        });
        wm.addView(root, lp);
        makeQuick();
        ask();
        ui.postDelayed(poll, 1500);
    }

    /** The Quick button sits over the empty spot right of Save on the Manage Party screen (a fixed spot of the game's layout). */
    private void makeQuick() {
        quick = new Button(this);
        try {
            java.io.InputStream in = getAssets().open("quick_icon.png");
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in);
            in.close();
            quick.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), bmp));
            quick.setText("");
        } catch (Exception e) {
            quick.setText("Quick");
            quick.setTextColor(0xfff2dea5);
            quick.setBackground(round(0xE6527c28));
        }
        quick.setPadding(0, 0, 0, 0);
        quick.setVisibility(View.GONE);
        quick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                quick.setVisibility(View.GONE);
                send("quick", true);
            }
        });
        qlp = new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        qlp.gravity = Gravity.TOP | Gravity.LEFT;
        placeQuick();
        wm.addView(quick, qlp);
        quickAdded = true;
    }

    private int lastW = -1, lastH = -1;

    /** Puts Quick on the spot right of Save. With landscape mode the game is a centred portrait strip, so the spot moves with it. */
    private void placeQuick() {
        android.graphics.Point real = new android.graphics.Point();
        wm.getDefaultDisplay().getRealSize(real);
        lastW = real.x; lastH = real.y;
        float gw = real.x, x0 = 0;
        if (Flags.EXP && real.x > real.y && getSharedPreferences("editor", MODE_PRIVATE).getBoolean("patchLand", false)) {
            gw = real.y * (float) real.y / real.x;       // same strip width the game patch uses
            x0 = (real.x - gw) / 2f;
        }
        int w = (int) (gw * 0.088f), h = (int) (w * 1.108f);
        qlp.width = w; qlp.height = h;
        qlp.x = (int) (x0 + gw * 0.9166f) - w / 2;
        qlp.y = (int) (real.y * 0.1416f) - h / 2;   // measured: the overlay lands about 4.5% of the height lower than asked
        if (quickAdded) { try { wm.updateViewLayout(quick, qlp); } catch (Exception ignored) { } }
    }

    private volatile boolean hooksUp;

    private final Runnable poll = new Runnable() {
        public void run() {
            if (root == null && !headless) return;
            ask();
            if (++pollN % 20 == 0) syncTick();       // about every 30 seconds
            ui.postDelayed(this, 1500);
        }
    };

    /** Saves the in-game log: straight into the sync folder when one is set, otherwise opens the app to choose a place. */
    private void exportLog() {
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    if (!Sync.useFolder(SpeedService.this)) {
                        Intent li = new Intent(SpeedService.this, MainActivity.class);
                        li.putExtra("savelog", true);
                        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(li);
                        return;
                    }
                    String r = Speed.qlog(5000);
                    byte[] b = r.getBytes("UTF-8");
                    if (b.length > 600000) {
                        int from = b.length - 600000;
                        while (from < b.length && b[from] != 10) from++;
                        b = java.util.Arrays.copyOfRange(b, Math.min(b.length, from + 1), b.length);
                    }
                    String v = "";
                    try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
                    String name = "ffbe_log" + (v == null || v.isEmpty() ? "" : "_v" + v + "_" + Sync.deviceTag()) + ".txt";
                    Sync.putFile(SpeedService.this, name, "text/plain", b);
                    Sync.cleanLogs(SpeedService.this);
                    msg = "Log saved to " + Sync.folderName(SpeedService.this) + ": " + name;
                } catch (Exception e) {
                    msg = "Could not save the log: " + e.getMessage();
                }
                final String m = msg;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() { public void run() { Toast.makeText(SpeedService.this, m, Toast.LENGTH_LONG).show(); } });
            }
        }).start();
    }

    /** Checks the cloud folder against the phone save. Quiet unless the player has to choose. */
    private void syncTick() {
        if (!cloudHere || !cloudOn || !wasGame || syncBusy.getAndSet(true)) return;
        new Thread(new Runnable() {
            public void run() {
                CloudSync.Result r = null;
                try {
                    r = CloudSync.tick(SpeedService.this);
                    getSharedPreferences("editor", MODE_PRIVATE).edit().putString("syncNote",
                            new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date()) + " " + CloudSync.lastNote).apply();
                } catch (Exception e) {
                    r = new CloudSync.Result();
                    r.key = "";
                    r.error = "Cloud sync: " + e.getMessage();
                } finally { syncBusy.set(false); }
                final CloudSync.Result rr = r;
                ui.post(new Runnable() {
                    public void run() {
                        if (rr.error != null && !("e|" + rr.error).equals(shownKey)) {
                            shownKey = "e|" + rr.error;
                            Toast.makeText(SpeedService.this, rr.error, Toast.LENGTH_LONG).show();
                        }
                        if (rr.kind == CloudSync.NONE || rr.key.equals(shownKey) || conflict != null) return;
                        shownKey = rr.key;
                        if (rr.kind == CloudSync.ASK_UP) showUp(rr.localTime);
                        else if (rr.kind == CloudSync.ASK_PULL) showAsk(rr.cloud, rr.localTime, rr.cloudTime);
                        else showConflict(rr.cloud, rr.localTime, rr.cloudTime);
                    }
                });
            }
        }).start();
    }

    /** Runs a sync action off the main thread, then reports. Restarts the game when the cloud save was loaded. */
    private void doSync(final int what, final String cloud) {
        new Thread(new Runnable() {
            public void run() {
                String e;
                if (what == 0) e = CloudSync.upload(SpeedService.this, false, null);          // first upload, no cloud file yet
                else if (what == 1) e = CloudSync.upload(SpeedService.this, true, null);      // keep phone: old cloud file backed up
                else e = CloudSync.download(SpeedService.this, cloud);
                final String ee = e;
                final boolean down = what == 2;
                ui.post(new Runnable() {
                    public void run() {
                        if (ee != null) { Toast.makeText(SpeedService.this, ee, Toast.LENGTH_LONG).show(); shownKey = ""; }
                        else if (down) {
                            Toast.makeText(SpeedService.this, "Loading the cloud save. The game restarts.", Toast.LENGTH_LONG).show();
                            ui.postDelayed(new Runnable() { public void run() { launchGame(); } }, 2200);
                        } else Toast.makeText(SpeedService.this, "Phone save uploaded.", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private LinearLayout box(CharSequence msg) {
        if (conflict != null) { try { wm.removeView(conflict); } catch (Exception ignored) { } conflict = null; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(0xF012204a));
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextColor(0xfff2dea5);
        t.setTextSize(14);
        box.addView(t);
        return box;
    }

    /** A save name in bright gold, bold and underlined. */
    private static CharSequence nm(String name) {
        android.text.SpannableString sp = new android.text.SpannableString(name);
        sp.setSpan(new android.text.style.ForegroundColorSpan(0xffffd54f), 0, name.length(), 0);
        sp.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, name.length(), 0);
        sp.setSpan(new android.text.style.UnderlineSpan(), 0, name.length(), 0);
        return sp;
    }

    private static CharSequence cat(Object... parts) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (Object o : parts) sb.append((CharSequence) o);
        return sb;
    }

    private Button choice(LinearLayout box, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setBackground(Ui.buttonBg(this));
        b.setTextColor(Ui.CREAM);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        box.addView(b);
        return b;
    }

    private void place(LinearLayout box) {
        WindowManager.LayoutParams cp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        cp.gravity = Gravity.CENTER;
        cp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
        try { wm.addView(box, cp); conflict = box; }
        catch (Exception e) { Toast.makeText(this, "Cloud sync has a question for you, but Android blocks the box. Allow \"Display over other apps\" for Kupo Forge (Speed button section) and try again.", Toast.LENGTH_LONG).show(); }
    }

    /** No save in the cloud folder yet: ask before uploading the phone save. */
    private void showUp(long localT) {
        LinearLayout b = box(cat("The cloud folder has no save yet. Upload the phone save (", nm(CloudSync.phoneName), ") there now?"));
        choice(b, "Yes, upload phone save " + CloudSync.stamp(localT), new View.OnClickListener() {
            public void onClick(View v) { hideConflict(); doSync(0, null); }
        });
        choice(b, "No, do not upload", new View.OnClickListener() {
            public void onClick(View v) { CloudSync.decline(SpeedService.this, null); hideConflict(); }
        });
        place(b);
    }

    /** Phone and cloud saves differ and both may have changed: ask which one to keep. Nothing is changed before the player picks. */
    private void showConflict(final String cloud, long localT, long cloudT) {
        LinearLayout b = box(cat("Save conflict: the phone save (", nm(CloudSync.phoneName), ") and the cloud save (", nm(CloudSync.cloudName), ") are different. Nothing was overwritten. Which one do you keep? "
                + "The other one is kept in the backups folder."));
        choice(b, "Keep phone save " + CloudSync.stamp(localT) + " (upload it now)", new View.OnClickListener() {
            public void onClick(View v) { hideConflict(); doSync(1, null); }
        });
        choice(b, "Keep cloud save " + CloudSync.stamp(cloudT) + " (restarts the game)", new View.OnClickListener() {
            public void onClick(View v) { hideConflict(); doSync(2, cloud); }
        });
        choice(b, "Decide later", new View.OnClickListener() {
            public void onClick(View v) { hideConflict(); }
        });
        place(b);
    }

    private void launchGame() {
        try {
            Intent li = getPackageManager().getLaunchIntentForPackage("com.exvius.offline.forever");
            if (li != null) { li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(li); }
        } catch (Exception ignored) { }
    }

    /** A newer cloud save is waiting (the phone save is unchanged): ask Yes or No. */
    private void showAsk(final String cloud, long localT, long cloudT) {
        LinearLayout b = box(cat("A newer cloud save (", nm(CloudSync.cloudName), ") was found. Sync it over the phone save (", nm(CloudSync.phoneName), ") now? "
                + "Yes loads it and restarts the game. Your phone save is backed up first. No keeps your phone save and does not ask again for this cloud file."));
        choice(b, "Yes, load cloud save " + CloudSync.stamp(cloudT) + " (restart the game)", new View.OnClickListener() {
            public void onClick(View v) { hideConflict(); doSync(2, cloud); }
        });
        choice(b, "No, keep my phone save " + CloudSync.stamp(localT), new View.OnClickListener() {
            public void onClick(View v) { CloudSync.decline(SpeedService.this, cloud); hideConflict(); }
        });
        place(b);
    }

    private void hideConflict() {
        if (conflict != null) { try { wm.removeView(conflict); } catch (Exception ignored) { } conflict = null; }
    }

    private Button toggle(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setTextColor(Color.WHITE);
        b.setOnClickListener(l);
        b.setVisibility(View.GONE);
        LinearLayout.LayoutParams q = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        q.topMargin = dp(4);
        b.setLayoutParams(q);
        return b;
    }

    private void ask() { send("?", false); }

    private void set(double v) {
        send(Double.toString(v), true);
        panel.setVisibility(View.GONE);
    }

    private void send(final String cmd, final boolean loud) {
        new Thread(new Runnable() {
            public void run() {
                String reply;
                try {
                    reply = Speed.send(cmd);
                } catch (Exception e) {
                    reply = null;
                }
                final String r = reply;
                ui.post(new Runnable() {
                    public void run() {
                        if (cmd.startsWith("cs ")) return;
                        if (cmd.startsWith("auto ") || cmd.startsWith("res ") || cmd.startsWith("rep ") || cmd.startsWith("bot ")) { ask(); return; }
                        if ("quick".equals(cmd)) {
                            if (r == null) Toast.makeText(SpeedService.this, "No answer from the game.", Toast.LENGTH_LONG).show();
                            else if (!r.startsWith("ok")) Toast.makeText(SpeedService.this, "Quick party: " + r, Toast.LENGTH_LONG).show();
                            return;
                        }
                        if ("?".equals(cmd)) hooksUp = r != null && r.startsWith("OK ");
                        shown(r, loud);
                    }
                });
            }
        }).start();
    }

    // ---- Gambit rules to the game: the active preset, compact one-line JSON, sent whenever the presets file changed ----

    private byte[] readAssetBytes(String name) throws Exception {
        java.io.InputStream in = getAssets().open(name);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    private void gamSync() {
        final java.io.File f = new java.io.File(getFilesDir(), "gambit_presets.json");
        final long m = f.exists() ? f.lastModified() : 0;
        if (m == lastGam) return;
        lastGam = m;
        new Thread(new Runnable() {
            public void run() {
                try {
                    String body = "{}", names = "{\"a\":-1,\"n\":[]}";
                    if (f.exists()) {
                        java.io.FileInputStream in = new java.io.FileInputStream(f);
                        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                        in.close();
                        Object o = Json.parse(new String(b.toByteArray(), "UTF-8"));
                        if (o instanceof java.util.Map) {
                            java.util.Map<String, Object> root = (java.util.Map<String, Object>) o;
                            Object lst = root.get("list");
                            Object act = root.get("active");
                            if (lst instanceof java.util.Map) {   // preset names for the in-game "Choose gambit" menu
                                StringBuilder nb2 = new StringBuilder("{\"a\":");
                                int ai = -1, ix = 0;
                                StringBuilder arr = new StringBuilder("[");
                                for (String key : ((java.util.Map<String, Object>) lst).keySet()) {
                                    if (key.equals(String.valueOf(act))) ai = ix;
                                    if (ix > 0) arr.append(',');
                                    arr.append('"').append(KupoInstall.ascii(key).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                                    ix++;
                                }
                                names = nb2.append(ai).append(",\"n\":").append(arr).append("]}").toString();
                            }
                            if (lst instanceof java.util.Map && act != null) {
                                Object cur = ((java.util.Map<String, Object>) lst).get(String.valueOf(act));
                                StringBuilder sb = new StringBuilder();
                                compact(sb, cur, 0);
                                body = sb.toString();
                                try {   // unit names for the on-screen notice: {"base id": "Name"} under "_n"
                                    Db dbn = new Db(new String(readAssetBytes("db.json"), "UTF-8"));
                                    java.util.Map<String, String> nm = new java.util.TreeMap<String, String>();
                                    for (Db.Unit du : dbn.units) for (Db.Star ds : du.stars.values()) if (ds.id.length() >= 8) nm.put(ds.id.substring(0, 8), du.name);
                                    StringBuilder nb = new StringBuilder(",\"_n\":{");
                                    boolean f1 = true;
                                    if (cur instanceof java.util.Map) for (String key : ((java.util.Map<String, Object>) cur).keySet()) {
                                        String nn = nm.get(key);
                                        if (nn == null) continue;
                                        if (!f1) nb.append(',');
                                        f1 = false;
                                        q(nb, key); nb.append(':'); q(nb, nn);
                                    }
                                    nb.append('}');
                                    if (!f1 && body.endsWith("}")) body = body.substring(0, body.length() - 1) + nb + "}";   // (the closing brace of the whole object was missing: the game could not read the rules)
                                } catch (Exception ignored) { }
                            }
                        }
                    }
                    String r = Speed.sendBig("gam " + body, 5000);
                    if (r == null || !r.startsWith("ok")) lastGam = -1;      // not understood (older patch): try again later
                    else Speed.send("gnames " + names);
                } catch (Exception e) {
                    lastGam = -1;
                }
            }
        }).start();
    }

    /** One-line JSON, pure ASCII, without the display names (the game only needs the ids). */
    @SuppressWarnings("unchecked")
    private static void compact(StringBuilder b, Object v, int depth) {
        if (v instanceof java.util.Map) {
            b.append('{');
            boolean first = true;
            java.util.Map<String, Object> mm = (java.util.Map<String, Object>) v;
            boolean isRule = mm.containsKey("c") && mm.containsKey("a");
            for (java.util.Map.Entry<String, Object> e : mm.entrySet()) {
                if (e.getKey().equals("an")) continue;
                if (!first) b.append(',');
                first = false;
                q(b, e.getKey());
                b.append(':');
                compact(b, e.getValue(), depth + 1);
            }
            if (isRule) {
                try { b.append(",\"tx\":"); q(b, Gambit.ruleText(mm)); } catch (Exception ex) { b.append("\"\""); }
            }
            b.append('}');
        } else if (v instanceof java.util.List) {
            b.append('[');
            boolean first = true;
            for (Object o : (java.util.List<Object>) v) {
                if (o instanceof java.util.Map && String.valueOf(((java.util.Map<String, Object>) o).get("a")).startsWith("item:")) continue;
                if (o instanceof java.util.Map && Gambit.usesOff(o)) continue;   // a rule with a turned-off condition (Foe race, Highest/Lowest) is kept in the file, not sent   // Use item is off for now: kept in the file, not sent
                if (!first) b.append(',');
                first = false;
                compact(b, o, depth + 1);
            }
            b.append(']');
        } else if (v instanceof Json.Num) {
            q(b, ((Json.Num) v).raw);
        } else if (v instanceof Boolean) {
            b.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof String) {
            q(b, (String) v);
        } else {
            b.append("null");
        }
    }

    private static void q(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 32 || c > 126) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        b.append('"');
    }

    /** The in-game "Choose gambit" menu picked preset number idx: make it the active one in the app's preset file; it goes to the game by gamSync. */
    @SuppressWarnings("unchecked")
    private void pickPreset(final int idx) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.io.File f = new java.io.File(getFilesDir(), "gambit_presets.json");
                    if (!f.exists()) return;
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    in.close();
                    Object o = Json.parse(new String(b.toByteArray(), "UTF-8"));
                    if (!(o instanceof java.util.Map)) return;
                    java.util.Map<String, Object> root = (java.util.Map<String, Object>) o;
                    Object lst = root.get("list");
                    if (!(lst instanceof java.util.Map)) return;
                    int i = 0;
                    for (String key : ((java.util.Map<String, Object>) lst).keySet()) {
                        if (i++ == idx) {
                            root.put("active", key);
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                            fo.write(Json.write(root).getBytes("UTF-8"));
                            fo.close();
                            getSharedPreferences("editor", MODE_PRIVATE).edit().putBoolean("gambitPicked", true).apply();
                            ui.post(new Runnable() { public void run() { gamSync(); } });
                            return;
                        }
                    }
                } catch (Exception ignored) { }
            }
        }).start();
    }

    private long logCachedAt;
    /** While the game answers, keeps a copy of its patch log on disk every 8 s, so the log can still be exported after a crash. */
    private void cacheLog() {
        long now = System.currentTimeMillis();
        if (now - logCachedAt < 8000) return;
        logCachedAt = now;
        if (Speed.logCache == null) Speed.logCache = new java.io.File(getFilesDir(), "last_kupo_log.txt");
        new Thread(new Runnable() { public void run() { try { Speed.qlog(4000); } catch (Exception ignored) { } } }).start();
    }

    /** Helper mode (no floating button): the same game answer is read for the hand-offs the moogle panel asks for. */
    private void headlessShown(String reply) {
        boolean inGame = reply != null && reply.startsWith("OK ");   // the game answers: sync does not need it in the foreground (floating windows, split screen)
        String[] p = inGame ? reply.split(" ") : new String[0];
        if (inGame && !wasGame) { lastCs = -1; lastGam = -1; shownKey = ""; ui.postDelayed(new Runnable() { public void run() { syncTick(); } }, 5000); }
        wasGame = inGame;
        if (inGame) cacheLog();
        noteCheck(inGame);
        if (!inGame) return;
        boolean hasC = Flags.EXP && p.length > 9 && !"cx".equals(p[9]);
        boolean hasB = Flags.BOT && p.length > 10 && !"kx".equals(p[10]);
        cloudHere = hasC;
        cloudOn = getSharedPreferences("editor", MODE_PRIVATE).getBoolean("syncAuto", true);
        if (hasB) gamSync();
        if (hasC && lastCs != (cloudOn ? 1 : 0)) { lastCs = cloudOn ? 1 : 0; send("cs " + lastCs, false); }
        if (p.length > 11 && p[11].startsWith("g") && p[11].indexOf(':') > 1) {
            try {
                int c = p[11].indexOf(':');
                int seq = Integer.parseInt(p[11].substring(1, c));
                String nm = p[11].substring(c + 1);
                if (lastReq < 0 || seq < lastReq) lastReq = seq;
                else if (seq != lastReq) {
                    lastReq = seq;
                    if ("cloud".equals(nm)) {
                        cloudOn = !cloudOn;
                        getSharedPreferences("editor", MODE_PRIVATE).edit().putBoolean("syncAuto", cloudOn).apply();
                        if (cloudOn) syncTick();
                    } else if ("edit".equals(nm)) {
                        try {
                            Intent li = new Intent(this, MainActivity.class);
                            li.putExtra("live", true);
                            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            startActivity(li);
                        } catch (Exception e) { Toast.makeText(this, "Could not open the editor: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                    } else if ("log".equals(nm)) exportLog();
                    else if ("fnote".equals(nm)) noteToggle();
                    else if (nm.startsWith("pick")) pickPreset(Integer.parseInt(nm.substring(4)));
                }
            } catch (Exception ignored) { }
        }
    }

    // ---- floating Gambit notice: an Android overlay window (outside the game's engine). Reads plain text lines with the "dnote" command. Click-through. ----
    private TextView noteV;
    private WindowManager.LayoutParams noteLp;
    private boolean noteAdded, noteLoop;
    private final java.util.concurrent.atomic.AtomicBoolean noteBusy = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Runnable noteTick = new Runnable() {
        public void run() {
            boolean on = Flags.EXP && Flags.BOT && wasGame && noteWanted() && android.provider.Settings.canDrawOverlays(SpeedService.this);
            if (!on) { noteLoop = false; noteHide(); return; }
            if (noteBusy.compareAndSet(false, true)) {
                new Thread(new Runnable() {
                    public void run() {
                        String r = null;
                        try { r = Speed.sendAll("dnote", 1200); } catch (Exception ignored) { }
                        final String t = r == null ? null : r.equals("none") ? "" : r;   // null = the game did not answer: keep what is shown
                        ui.post(new Runnable() { public void run() { noteShow(t); noteBusy.set(false); } });
                    }
                }).start();
            }
            ui.postDelayed(this, 800);
        }
    };

    /** Patch switch on (menu entry exists) and the moogle menu toggle on. */
    private boolean noteWanted() {
        android.content.SharedPreferences sp = getSharedPreferences("editor", MODE_PRIVATE);
        return Flags.LOGS && sp.getBoolean("patchOvNote", false) && sp.getBoolean("ovNoteOn", false);
    }

    private long hintUntil;
    private int noteLastFn = -1;

    /** The moogle menu asked to flip the floating log: remember it, tell the game (so the menu shows ON/OFF), and show a drag hint for a few seconds. */
    private void noteToggle() {
        android.content.SharedPreferences sp = getSharedPreferences("editor", MODE_PRIVATE);
        boolean now = !sp.getBoolean("ovNoteOn", false);
        sp.edit().putBoolean("ovNoteOn", now).apply();
        noteLastFn = now ? 1 : 0;
        send("fn " + noteLastFn, false);
        if (now) {
            hintUntil = System.currentTimeMillis() + 8000;
            if (!android.provider.Settings.canDrawOverlays(this))
                Toast.makeText(this, "Allow \"Display over other apps\" for Kupo Forge to see the floating log.", Toast.LENGTH_LONG).show();
            noteCheck(true);
        } else noteHide();
    }

    private void noteCheck(boolean inGame) {
        if (!inGame) { noteLastFn = -1; return; }   // the game closed: tell it the state again when it comes back
        if (noteLastFn < 0) {
            noteLastFn = getSharedPreferences("editor", MODE_PRIVATE).getBoolean("ovNoteOn", false) ? 1 : 0;
            send("fn " + noteLastFn, false);
        }
        if (inGame && !noteLoop && Flags.EXP && Flags.BOT && noteWanted()) {
            noteLoop = true;
            ui.post(noteTick);
        }
    }

    private String noteShown = "";
    private boolean notePaused;
    private static final float[] NOTE_SIZES = {11f, 13f, 16f};

    private float noteSize() {
        int i = getSharedPreferences("editor", MODE_PRIVATE).getInt("ovNoteSize", 1);
        return NOTE_SIZES[Math.max(0, Math.min(NOTE_SIZES.length - 1, i))];
    }

    /** Fills the box in place. The window is added once and stays while the log is wanted: only the text changes, so nothing blinks. */
    private void noteShow(String t) {
        if (t == null) return;                                   // no answer this time: leave the box as it is
        if (t.isEmpty()) t = System.currentTimeMillis() < hintUntil ? "Gambit log (drag me)" : "Gambit log: waiting for a decision";
        if (notePaused) { if (noteV != null) noteV.setAlpha(0.75f); return; }
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (noteV == null) {
            noteV = new TextView(this);
            noteV.setTextColor(Ui.CREAM);
            noteV.setTextSize(noteSize());
            noteV.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.92f));
            int p = Ui.dp(this, 8);
            noteV.setPadding(p, p / 2, p, p / 2);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(0xe60c1e3c);
            g.setCornerRadius(Ui.dp(this, 8));
            g.setStroke(Math.max(1, Ui.dp(this, 1)), Ui.GOLD);
            noteV.setBackground(g);
            noteV.setOnTouchListener(new View.OnTouchListener() {
                float dx, dy; int sx, sy; long t0; boolean moved;
                public boolean onTouch(View v, android.view.MotionEvent e) {
                    if (noteLp == null) return false;
                    switch (e.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN: dx = e.getRawX(); dy = e.getRawY(); sx = noteLp.x; sy = noteLp.y; t0 = System.currentTimeMillis(); moved = false; return true;
                        case android.view.MotionEvent.ACTION_MOVE: {
                            if (Math.abs(e.getRawX() - dx) + Math.abs(e.getRawY() - dy) > Ui.dp(SpeedService.this, 8)) moved = true;
                            if (!moved) return true;
                            android.util.DisplayMetrics m = getResources().getDisplayMetrics();
                            noteLp.x = Math.max(0, Math.min(m.widthPixels - v.getWidth(), sx + (int) (e.getRawX() - dx)));
                            noteLp.y = Math.max(0, Math.min(m.heightPixels - v.getHeight(), sy + (int) (e.getRawY() - dy)));
                            try { wm.updateViewLayout(v, noteLp); } catch (Exception ignored) { }
                            return true;
                        }
                        case android.view.MotionEvent.ACTION_UP:
                            if (moved) getSharedPreferences("editor", MODE_PRIVATE).edit().putInt("ovNoteX", noteLp.x).putInt("ovNoteY", noteLp.y).apply();
                            else if (System.currentTimeMillis() - t0 > 700) {   // hold: next text size
                                int i = (getSharedPreferences("editor", MODE_PRIVATE).getInt("ovNoteSize", 1) + 1) % NOTE_SIZES.length;
                                getSharedPreferences("editor", MODE_PRIVATE).edit().putInt("ovNoteSize", i).apply();
                                ((TextView) v).setTextSize(NOTE_SIZES[i]);
                            } else {                                              // tap: pause or resume
                                notePaused = !notePaused;
                                ((TextView) v).setAlpha(notePaused ? 0.75f : 1f);
                                if (notePaused) ((TextView) v).setText("[paused: tap to resume]\n" + noteShown);
                                else noteShown = "";                            // redraw at the next poll
                            }
                            return true;
                    }
                    return false;
                }
            });
        }
        if (!t.equals(noteShown)) { noteShown = t; noteV.setText(t); }
        noteV.setAlpha(1f);
        if (!noteAdded) {
            WindowManager.LayoutParams lp2 = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp2.gravity = Gravity.TOP | Gravity.LEFT;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.content.SharedPreferences sp = getSharedPreferences("editor", MODE_PRIVATE);
            lp2.x = Math.max(0, Math.min(dm.widthPixels - Ui.dp(this, 60), sp.getInt("ovNoteX", Ui.dp(this, 8))));
            lp2.y = Math.max(0, Math.min(dm.heightPixels - Ui.dp(this, 40), sp.getInt("ovNoteY", (int) (dm.heightPixels * 0.62f))));
            noteLp = lp2;
            try { wm.addView(noteV, lp2); noteAdded = true; } catch (Exception ignored) { }
        }
    }

    private void noteHide() {
        if (noteAdded && noteV != null && wm != null) { try { wm.removeView(noteV); } catch (Exception ignored) { } }
        noteAdded = false;
        noteShown = "";
    }

    private void shown(String reply, boolean loud) {
        if (bubble == null) { if (headless) headlessShown(reply); return; }
        if (quick != null) {
            android.graphics.Point rs = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(rs);
            if (rs.x != lastW || rs.y != lastH) placeQuick();      // the phone was turned
        }
        boolean inGame = reply != null && reply.startsWith("OK ");
        if (inGame) {
            String[] q = reply.split(" ");
            inGame = q.length < 4 || !"0".equals(q[3]);       // older patch has no foreground flag
        }
        if (inGame && !wasGame) { lastCs = -1; lastGam = -1; shownKey = ""; ui.postDelayed(new Runnable() { public void run() { syncTick(); } }, 5000); }
        if (reply != null && reply.startsWith("OK ")) cacheLog();
        wasGame = reply != null && reply.startsWith("OK ");   // sync and hand-offs work while the game is behind another window
        int want = inGame ? View.VISIBLE : View.GONE;
        if (root.getVisibility() != want) root.setVisibility(want);
        if (!inGame) { panel.setVisibility(View.GONE); if (quick != null) quick.setVisibility(View.GONE); }
        if (reply == null || !reply.startsWith("OK ")) {
            bubble.setText("!");
            bubble.setTextColor(0xFFFF5252);
            if (loud) Toast.makeText(this, "No answer from the game. Is the game running, and is the script installed?",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String[] p = reply.split(" ");
        boolean wantQuick = false;   // Quick party is now a button the game hook draws on the Manage Party screen
        if (editorFront) wantQuick = false;
        quick.setVisibility(wantQuick ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(quickExpire);
        if (wantQuick) ui.postDelayed(quickExpire, 4000);   // no fresh "yes" from the game in 4 s: hide
        boolean hasA = Flags.EXP && p.length > 6 && !"ax".equals(p[6]);
        boolean hasS = Flags.EXP && p.length > 7 && !"sx".equals(p[7]);
        boolean hasR = Flags.EXP && p.length > 8 && !"rx".equals(p[8]);
        autoOn = p.length > 6 && "a1".equals(p[6]);
        resOn = p.length > 7 && "s1".equals(p[7]);
        repOn = p.length > 8 && "r1".equals(p[8]);
        resBtn.setVisibility(hasS ? View.VISIBLE : View.GONE);
        autoBtn.setVisibility(hasA ? View.VISIBLE : View.GONE);
        repBtn.setVisibility(hasR ? View.VISIBLE : View.GONE);
        resBtn.setText("Skip results: " + (resOn ? "ON" : "OFF"));
        autoBtn.setText("Auto battle: " + (autoOn ? "ON" : "OFF"));
        repBtn.setText("Repeat battle: " + (repOn ? "ON" : "OFF"));
        editBtn.setVisibility(Flags.EXP && getSharedPreferences("editor", MODE_PRIVATE).getBoolean("patchLive", false) ? View.VISIBLE : View.GONE);
        boolean hasC = Flags.EXP && p.length > 9 && !"cx".equals(p[9]);
        boolean hasB = Flags.BOT && p.length > 10 && !"kx".equals(p[10]);
        botOn = p.length > 10 && "k1".equals(p[10]);
        botBtn.setVisibility(hasB ? View.VISIBLE : View.GONE);
        botBtn.setText("Gambit: " + (botOn ? "ON" : "OFF"));
        botBtn.setBackground(round(botOn ? 0xCC1B7F3B : 0xCC444444));
        cloudHere = hasC;
        cloudOn = getSharedPreferences("editor", MODE_PRIVATE).getBoolean("syncAuto", true);
        if (hasB) gamSync();
        if (hasC && lastCs != (cloudOn ? 1 : 0)) { lastCs = cloudOn ? 1 : 0; send("cs " + lastCs, false); }
        if (p.length > 11 && p[11].startsWith("g") && p[11].indexOf(':') > 1) {
            try {
                int c = p[11].indexOf(':');
                int seq = Integer.parseInt(p[11].substring(1, c));
                String nm = p[11].substring(c + 1);
                if (lastReq < 0 || seq < lastReq) lastReq = seq;
                else if (seq != lastReq) {
                    lastReq = seq;
                    if ("cloud".equals(nm) && cloudBtn != null) cloudBtn.performClick();
                    else if ("edit".equals(nm) && editBtn != null) editBtn.performClick();
                    else if ("log".equals(nm)) exportLog();
                    else if (nm.startsWith("pick")) pickPreset(Integer.parseInt(nm.substring(4)));
                }
            } catch (Exception ignored) { }
        }
        cloudBtn.setVisibility(hasC ? View.VISIBLE : View.GONE);
        cloudBtn.setText("Cloud sync: " + (cloudOn ? "ON" : "OFF"));
        cloudBtn.setBackground(round(cloudOn ? 0xCC1B7F3B : 0xCC444444));
        resBtn.setBackground(round(resOn ? 0xCC1B7F3B : 0xCC444444));
        autoBtn.setBackground(round(autoOn ? 0xCC1B7F3B : 0xCC444444));
        repBtn.setBackground(round(repOn ? 0xCC1B7F3B : 0xCC444444));
        try { current = Double.parseDouble(p[1]); } catch (Exception ignored) { }
        String state = p.length > 2 ? p[2] : "";
        bubble.setText(Speed.label(current));
        if ("ready".equals(state)) {
            bubble.setTextColor(current == 1 ? Color.WHITE : 0xFF69F0AE);
        } else {
            bubble.setTextColor(0xFFFFB74D);
            if (loud) Toast.makeText(this, "The game answered but its speed hook is not ready (" + state + ").",
                    Toast.LENGTH_LONG).show();
        }
    }

    public void onDestroy() {
        running = false;
        if (inst == this) inst = null;
        ui.removeCallbacks(poll);
        hideConflict();
        if (quickAdded && quick != null) { try { wm.removeView(quick); } catch (Exception ignored) { } quickAdded = false; }
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) { }
            root = null;
        }
        super.onDestroy();
    }
}
