package com.ffbeforever.saveedit;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.WindowInsets;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int OPEN_SAVE = 1, WRITE_SAVE = 2, OPEN_APK = 3, WRITE_APK = 4, INSTALL_APK = 5, PICK_ICONS = 6, SYNC_TREE = 7, WRITE_TABLES = 8, WRITE_GAMBIT = 9, OPEN_GAMBIT = 10, WRITE_LOG = 11;
    private byte[] pendingLog;
    private TextView pendingLogRes;
    private Gambit gambit;
    private View gambitView;
    private String pendingGambit = "";
    private TextView impText;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Db db;
    private Editor ed;
    private String saveName = "save.json";

    private LinearLayout content;
    private View editorView, patchView;
    private View[] sections;
    private TextView[] subTabs;
    private LinearLayout sectionHost;
    private Runnable unitsFill, itemsFill;
    private BaseAdapter unitsAdapter;
    private TextView[] tabs;
    private static final int STRIP = Ui.NIGHT, PAGE = Ui.NAVY, TAB_ON = Ui.CREAM, TAB_OFF = Ui.DIM;

    // profile tab
    private boolean filling;

    // editor tab
    private TextView status;
    private EditText search;
    private ListView list;
    private ArrayAdapter<String> adapter;
    private final List<Integer> shown = new ArrayList<Integer>();
    private final Set<String> picked = new HashSet<String>();
    private CheckBox maxLb;
    private Spinner starPick;
    private int sortKey = 0;          // 0 save order, 1 name, 2 rarity, 3 level
    private boolean sortDown = false;

    // patch tab
    private TextView patchLog;
    private TextView speedState;
    private File patched;
    private boolean busy;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private Button button(String text, View.OnClickListener on) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(on);
        return b;
    }

    private void addW(LinearLayout l, View v) {
        l.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void alert(String title, String msg) {
        new Dlg(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    // ------------------------------------------------------------------ setup

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getSharedPreferences("editor", MODE_PRIVATE).edit().putBoolean("speedAuto", false).apply();   // the old floating speed bubble stays off, also for players who had it on
        Speed.logCache = new java.io.File(getFilesDir(), "last_kupo_log.txt");
        Icons.init(this);
        try {
            db = new Db(new String(readAll(getAssets().open("db.json")), "UTF-8"));
        } catch (Exception e) {
            alert("Data missing", "Could not load the unit list: " + e);
        }
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(STRIP);
        // Title with the version from the installed package, so it always matches the build.
        String ver = "";
        try { ver = " v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        String title = "FFBE Kupo Forge" + ver;
        setTitle(title);
        if (getActionBar() != null) {
            getActionBar().setTitle(title);
            getActionBar().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(STRIP));
        } else {
            TextView head = new TextView(this);
            head.setText(title);
            head.setTextSize(17);
            head.setTypeface(Typeface.SERIF, Typeface.BOLD);
            head.setTextColor(Ui.GOLD);
            head.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout headRow = row();
            headRow.setGravity(Gravity.CENTER_VERTICAL);
            headRow.setPadding(dp(12), dp(6), dp(10), dp(2));
            head.setPadding(0, 0, dp(8), 0);
            headRow.addView(head, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));   // title left, takes the free space
            try {   // Kupo Forge mascot, transparent background, top right
                java.io.InputStream mi = getAssets().open("mascot_head.png");
                android.graphics.Bitmap mb = android.graphics.BitmapFactory.decodeStream(mi);
                mi.close();
                android.widget.ImageView mv = new android.widget.ImageView(this);
                mv.setImageBitmap(mb);
                mv.setAdjustViewBounds(true);
                headRow.addView(mv, new LinearLayout.LayoutParams(dp(44), dp(40)));
            } catch (Exception ignored) { }
            top.addView(headRow);
        }
        LinearLayout bar = row();
        bar.setPadding(dp(6), dp(6), dp(6), 0);
        String[] names = {"Save Editor", "Gambit", "Game Patch"};
        tabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(14);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(10), dp(11), dp(10), dp(11));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { tab(k); }
            });
            tabs[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            bar.addView(t, lp);
        }
        top.addView(bar);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(PAGE);
        content.setPadding(dp(10), dp(8), dp(10), dp(6));
        top.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        editorView = buildSaveEditor();
        patchView = buildPatch();
        gambit = new Gambit(this, new Gambit.Host() {
            public Editor ed() { return ed; }
            public Db db() { return db; }
            public void changed() { if (ed != null) { ed.dirty = true; updateStatus(); keepPresets(); } }
            public void startExport(String json) {
                pendingGambit = json;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, vname("ffbe_gambit.json"));
                startActivityForResult(i, WRITE_GAMBIT);
            }
            public void apply() {   // no restart: only the rules go to the running game (the save file is not written)
                keepPresets();
                toast(SpeedService.pokeForce() ? "Gambit rules sent to the game. No restart." : "The helper is not running yet. Open the game with the script installed and try again.");
            }
            public void startImport() {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, OPEN_GAMBIT);
            }
        });
        gambitView = gambit.view();
        Ui.apply(editorView);
        Ui.apply(gambitView);
        Ui.apply(patchView);
        getWindow().getDecorView().setBackgroundColor(STRIP);
        getWindow().setStatusBarColor(STRIP);        // no effect on Android 15+ (edge to edge), the strip shows through
        getWindow().setNavigationBarColor(PAGE);
        setContentView(top);
        fitBars(top);
        tab(0);
        handleLive(getIntent());
    }

    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleLive(i);
    }

    /** Started from the floating circle: show the Save Editor tab and read the save from the game. */
    private void handleLive(Intent i) {
        if (i != null && i.getBooleanExtra("savelog", false)) {
            i.removeExtra("savelog");
            tab(2);
            saveLogDialog();
            return;
        }
        if (i == null || !i.getBooleanExtra("live", false)) return;
        i.removeExtra("live");
        tab(0);
        if (Flags.EXP) liveOpen();
    }

    /** Reads the in-game log and lets the player choose where to save it (used when there is no sync folder). */
    private void saveLogDialog() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String lg = Speed.qlog(5000);
                    byte[] lb = lg.getBytes("UTF-8");
                    if (lb.length > 600000) {
                        int from = lb.length - 600000;
                        while (from < lb.length && lb[from] != 10) from++;
                        lb = java.util.Arrays.copyOfRange(lb, Math.min(lb.length, from + 1), lb.length);
                    }
                    pendingLog = lb;
                    pendingLogRes = null;
                    ui.post(new Runnable() { public void run() {
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_TITLE, vname("ffbe_log.txt"));
                        startActivityForResult(i, WRITE_LOG);
                    } });
                } catch (Exception e) {
                    final String m = String.valueOf(e.getMessage());
                    ui.post(new Runnable() { public void run() { alert("Could not read the log", m + "\n\nThe game must be open with the script installed."); } });
                }
            }
        }).start();
    }

    private void tab(int k) {
        content.removeAllViews();
        View v = k == 0 ? editorView : k == 1 ? gambitView : patchView;
        if (k == 1 && gambit != null) { gambit.refresh(); gambit.autoLoadNames(); }
        content.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == k;
            float r = dp(10);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});   // round top corners only, like a browser tab
            g.setColor(on ? PAGE : 0x00000000);
            g.setStroke(Math.max(2, dp(2)), 0xffd4b46a);      // gold border on every tab
            if (false) {
                // thin divider on the right of an inactive tab, as browsers draw between flat tabs
                GradientDrawable d = new GradientDrawable();
                d.setColor(0x60d4b46a);
                android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{g, d});
                l.setLayerInset(1, 0, dp(10), 0, dp(10));
                l.setLayerGravity(1, Gravity.RIGHT | Gravity.FILL_VERTICAL);
                l.setLayerWidth(1, Math.max(1, dp(1)));
                tabs[i].setBackground(l);
            } else {
                tabs[i].setBackground(g);
            }
            tabs[i].setTextColor(on ? TAB_ON : TAB_OFF);
            tabs[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    /**
     * Android 15+ draws apps targeting API 35+ edge to edge (behind the status and navigation bars) and
     * Android 16 removed the opt-out. Pad the view by the bars, the cutout and the keyboard so nothing hides.
     */
    void fitBars(final View v) {
        if (Build.VERSION.SDK_INT < 35) return;
        final int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View x, WindowInsets in) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                x.setPadding(l + i.left, t + i.top, r + i.right, b + i.bottom);
                return WindowInsets.CONSUMED;
            }
        });
        v.requestApplyInsets();
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------ editor tab

    private View buildSaveEditor() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r1 = row();
        addW(r1, button("Open save", new View.OnClickListener() {
            public void onClick(View x) { openSave(); }
        }));
        addW(r1, button("Save edited copy", new View.OnClickListener() {
            public void onClick(View x) { saveCopy(); }
        }));
        v.addView(r1);
        if (Flags.EXP) {
            LinearLayout r2 = row();
            addW(r2, button("Open from game", new View.OnClickListener() {
                public void onClick(View x) { liveOpen(); }
            }));
            addW(r2, button("Apply to game", new View.OnClickListener() {
                public void onClick(View x) { liveApply(); }
            }));
            v.addView(r2);
        }
        status = text("Export your save from the game's title screen, then tap Open save.");
        v.addView(status);
        LinearLayout chips = row();
        chips.setPadding(0, dp(2), 0, dp(6));
        // Bestiary, Missions and Summon tabs are in every build.
        String[] names = Flags.BOT ? new String[]{"Add Units", "Edit Units", "TMR/Equipment", "Awakening", "Bestiary", "Missions", "Summon"}
                : new String[]{"Add Units", "Edit Units", "TMR/Equipment"};
        subTabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(12);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(12), dp(8), dp(12), dp(8));
            t.setTag("keep");
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) { section(k); }
            });
            subTabs[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(2), 0, dp(2), 0);
            chips.addView(t, lp);
        }
        // The tab row scrolls left and right when the tabs do not fit on the screen.
        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setFillViewport(true);
        chipScroll.addView(chips, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        v.addView(chipScroll);
        sectionHost = new LinearLayout(this);
        sectionHost.setOrientation(LinearLayout.VERTICAL);
        v.addView(sectionHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        sections = Flags.BOT ? new View[]{buildAddUnits(), buildEditUnits(), buildItems(), buildMaterials(), buildBestiary(), buildMissions(), buildSummon()}
                : new View[]{buildAddUnits(), buildEditUnits(), buildItems()};
        section(1);
        return v;
    }

    /** Show one Save Editor section; the active chip gets the gold edge. */
    private void section(int k) {
        sectionHost.removeAllViews();
        sectionHost.addView(sections[k], new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < subTabs.length; i++) {
            boolean on = i == k;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(16));
            g.setColor(on ? Ui.PANEL : 0x00000000);
            g.setStroke(Math.max(1, dp(1)), on ? Ui.GOLD : 0x50d4b46a);
            subTabs[i].setBackground(g);
            subTabs[i].setTextColor(on ? Ui.CREAM : Ui.DIM);
            subTabs[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        if ((k == 0 || k == 1) && unitsFill != null) unitsFill.run();
        if (k == 2 && itemsFill != null) itemsFill.run();
        if (Flags.BOT) {   // Awakening, Bestiary, Missions, Summon follow the TMR/Equipment tab
            if (k == 3 && matFill != null) matFill.run();
            if (k == 4 && bestFill != null) bestFill.run();
            if (k == 5 && missFill != null) missFill.run();
            if (k == 6 && summFill != null) summFill.run();
        }
    }

    // ------------------------------------------------------------------ awakening materials

    /** Every material the awakening table (F_UNIT_CLASS_UP_MST) asks for: {id, name}. Plus the Omniprism that stands in for unit prisms. */
    private List<String[]> awakenMats() {
        List<String[]> out = new ArrayList<String[]>();
        out.add(new String[]{"lapis_free", "Lapis", "", "cur"});
        out.add(new String[]{"gil", "Gil", "", "cur"});
        // stat pots and doors are numbers in the save's enhancement-point bank, not items: bank:<token>
        String[] pt = {"LD4UJaf0", "ui34MYgo", "5sNq90o6", "0xgsDY9E", "VSL4Wj9z", "y7Z5AmhC"}, dt = {"h5s68EtJ", "eu2DXK4I", "8RUQtk4i", "8cj19yhb", "rjK5oB1H", "16NCtrGw"};
        String[] sn = {"HP", "MP", "ATK", "DEF", "MAG", "SPR"};
        for (int k = 0; k < 6; k++) out.add(new String[]{"bank:" + pt[k], sn[k] + " Pot", "", "pot"});
        for (int k = 0; k < 6; k++) out.add(new String[]{"bank:" + dt[k], sn[k] + " Door", "", "door"});
        try {
            Object o = Json.parse(new String(readAll(getAssets().open("awaken_mats.json")), "UTF-8"));
            for (Object e : (List<Object>) o) {
                List<Object> r = (List<Object>) e;
                String id = Editor.str(r.get(0)), lv = "";
                if (r.size() > 2 && r.get(2) instanceof List) for (Object x : (List<Object>) r.get(2)) lv += (lv.isEmpty() ? "" : ",") + Editor.str(x);
                if (r.size() > 3 && Editor.num(r.get(3)) != 0) lv += (lv.isEmpty() ? "" : ",") + "nv";
                String kind = id.startsWith("300") || id.startsWith("310") ? "prism" : id.startsWith("250") || id.startsWith("360") ? "fragment" : "other";
                out.add(new String[]{id, Editor.str(r.get(1)), lv, kind, r.size() > 4 ? Editor.str(r.get(4)) : id});
            }
        } catch (Exception ex) { }
        return out;
    }

    /** Filter popup of the Awakening tab: levels (multi-select), prisms, fragments, only owned. */
    private void materialFilter(final boolean[] lvOn, final String[] lvNames, final boolean[] opt, final Runnable fill) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(4));
        TextView lt = text("Awakening level (materials needed to awaken to...)");
        lt.setTextColor(Ui.GOLD);
        box.addView(lt);
        final Button lb = button(levelText(lvOn, lvNames) + "  \u25be", null);
        lb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                final boolean[] tmp = lvOn.clone();
                new Dlg(MainActivity.this).setTitle("Awakening levels")
                        .setMultiChoiceItems(lvNames, tmp, new DialogInterface.OnMultiChoiceClickListener() {
                            public void onClick(DialogInterface d, int which, boolean on) { tmp[which] = on; }
                        }).setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                System.arraycopy(tmp, 0, lvOn, 0, tmp.length);
                                lb.setText(levelText(lvOn, lvNames) + "  \u25be");
                                fill.run();
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
        box.addView(lb);
        String[] labels = {"Show prisms", "Show fragments", "Only materials I own", "Show pots", "Show doors"};
        for (int k = 0; k < 5; k++) {
            final int kk = k;
            CheckBox cb = new CheckBox(this);
            cb.setText(labels[k]);
            cb.setChecked(opt[k]);
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean c) { opt[kk] = c; fill.run(); }
            });
            box.addView(cb);
        }
        new Dlg(this).setTitle("Filter").setView(box).setPositiveButton("Done", null).show();
    }

    private static String levelText(boolean[] on, String[] names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < on.length; i++) if (on[i]) sb.append(sb.length() == 0 ? "" : ", ").append(names[i]);
        return sb.length() == 0 ? "All levels" : sb.toString();
    }

    private static boolean isCur(String id) { return id.equals("gil") || id.equals("lapis_free"); }
    private static boolean isBank(String id) { return id.startsWith("bank:"); }
    private long matOwned(String id) { return ed == null ? 0 : isCur(id) ? ed.currency(id) : isBank(id) ? ed.bank(id.substring(5)) : ed.owned(20, Long.parseLong(id)); }
    private void matSet(String id, long n) { if (n < 0) n = 0; if (isCur(id)) ed.setCurrency(id, n); else if (isBank(id)) ed.setBank(id.substring(5), n); else ed.setItemCount(20, Long.parseLong(id), n); }

    private View buildMaterials() {
        final List<String[]> all = awakenMats();
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        final TextView info = small("");
        v.addView(info);
        final EditText q = new EditText(this);
        q.setHint("Search awakening materials");
        q.setSingleLine(true);
        final boolean[] lvOn = new boolean[7];            // 2★..7★ and Neo Vision; none ticked = every level
        final boolean[] opt = new boolean[]{true, true, false, true, true};   // show prisms, show fragments, only owned, show pots, show doors
        final String[] lvNames = {"2\u2605", "3\u2605", "4\u2605", "5\u2605", "6\u2605", "7\u2605", "Neo Vision"};
        final String[] lvKeys = {"2", "3", "4", "5", "6", "7", "nv"};
        final List<String[]> shownM = new ArrayList<String[]>();
        final java.util.Set<String> ticked = new java.util.HashSet<String>();   // rows the player ticked
        final Runnable[] fill = new Runnable[1];
        LinearLayout sr = row();
        sr.setGravity(Gravity.CENTER_VERTICAL);
        addW(sr, q);
        sr.addView(filterButton(new View.OnClickListener() {
            public void onClick(View x) { materialFilter(lvOn, lvNames, opt, fill[0]); }
        }), miniLp());
        v.addView(sr);
        LinearLayout selRow = row();
        addW(selRow, button("Select all", new View.OnClickListener() {
            public void onClick(View x) { for (String[] m : shownM) ticked.add(m[0]); fill[0].run(); }
        }));
        addW(selRow, button("Unselect all", new View.OnClickListener() {
            public void onClick(View x) { ticked.clear(); fill[0].run(); }
        }));
        v.addView(selRow);
        v.addView(button("Fill ticked (or all shown) to a number...", new View.OnClickListener() {
            public void onClick(View x) {
                if (ed == null) { toast("Open a save first."); return; }
                final EditText n = new EditText(MainActivity.this);
                n.setInputType(InputType.TYPE_CLASS_NUMBER);
                n.setText("99");
                new Dlg(MainActivity.this).setTitle("Fill shown materials").setMessage("Raises every ticked material that is below this number up to it (if none is ticked, every shown one). Never lowers a count. Max 9999.")
                        .setView(n).setPositiveButton("Fill", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                long c;
                                try { c = Long.parseLong(n.getText().toString().trim()); } catch (Exception e) { return; }
                                int k = 0;
                                if (c < 0) c = 0;
                                boolean anyT = false;
                                for (String[] m : shownM) if (ticked.contains(m[0])) anyT = true;
                                for (String[] m : shownM) {
                                    if (anyT && !ticked.contains(m[0])) continue;
                                    if (isCur(m[0])) continue;   // Lapis and Gil are not part of "fill"
                                    long have = ed.owned(20, Long.parseLong(m[0]));
                                    if (have < c) { ed.setItemCount(20, Long.parseLong(m[0]), c); k++; }
                                }
                                toast("Raised " + k + " materials.");
                                updateStatus();
                                fill[0].run();
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        }));
        final ListView lv = new ListView(this);
        lv.setItemsCanFocus(true);
        final BaseAdapter ad = new BaseAdapter() {
            public int getCount() { return shownM.size(); }
            public Object getItem(int pos) { return shownM.get(pos); }
            public long getItemId(int pos) { return pos; }
            public View getView(final int pos, View old, ViewGroup parent) {
                LinearLayout r = row();
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(dp(4), dp(6), dp(4), dp(6));
                final String[] m = shownM.get(pos);
                long have = matOwned(m[0]);
                CheckBox tk = new CheckBox(MainActivity.this);
                tk.setChecked(ticked.contains(m[0]));
                tk.setFocusable(false);
                tk.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View x) { if (((CheckBox) x).isChecked()) ticked.add(m[0]); else ticked.remove(m[0]); }
                });
                r.addView(tk);
                TextView t = new TextView(MainActivity.this);
                t.setPadding(dp(6), 0, 0, 0);
                t.setText(m[1] + "\n" + have + " owned");
                t.setTextSize(14);
                t.setTextColor(Ui.TEXT);
                r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button set = button("Set", new View.OnClickListener() {
                    public void onClick(View x) {
                        if (ed == null) return;
                        final EditText n = new EditText(MainActivity.this);
                        n.setInputType(InputType.TYPE_CLASS_NUMBER);
                        n.setText(String.valueOf(matOwned(m[0])));
                        new Dlg(MainActivity.this).setTitle(m[1]).setMessage(isCur(m[0]) ? "How many to own (0 to 9,999,999,999)" : "How many to own (0 to 9999)")
                                .setView(n).setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        try { matSet(m[0], Long.parseLong(n.getText().toString().trim())); } catch (Exception e) { return; }
                                        updateStatus();
                                        fill[0].run();
                                    }
                                }).setNegativeButton("Cancel", null).show();
                    }
                });
                final long step = m[0].equals("gil") ? 100000 : m[0].equals("lapis_free") ? 1000 : 10;
                Button plus = button("+" + step, new View.OnClickListener() {
                    public void onClick(View x) {
                        if (ed == null) return;
                        matSet(m[0], matOwned(m[0]) + step);
                        updateStatus();
                        fill[0].run();
                    }
                });
                set.setFocusable(false);
                plus.setFocusable(false);
                for (Button bt : new Button[]{set, plus}) { bt.setBackground(Ui.buttonBg(MainActivity.this)); bt.setTextColor(Ui.CREAM); }
                r.addView(plus);
                r.addView(set);
                return r;
            }
        };
        lv.setAdapter(ad);
        v.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        fill[0] = new Runnable() {
            public void run() {
                String f = q.getText().toString().trim().toLowerCase(Locale.ROOT);
                shownM.clear();
                for (String[] m : all) {
                    if (!f.isEmpty() && !m[1].toLowerCase(Locale.ROOT).contains(f) && !m[0].contains(f)) continue;
                    if (opt[2] && matOwned(m[0]) <= 0) continue;
                    if (m[3].equals("prism") && !opt[0]) continue;
                    if (m[3].equals("fragment") && !opt[1]) continue;
                    if (m[3].equals("pot") && !opt[3]) continue;
                    if (m[3].equals("door") && !opt[4]) continue;
                    boolean anyLv = false, hit = false;
                    for (int z = 0; z < 7; z++) if (lvOn[z]) { anyLv = true; if (("," + m[2] + ",").contains("," + lvKeys[z] + ",")) hit = true; }
                    if (anyLv && !m[3].equals("cur") && !m[3].equals("pot") && !m[3].equals("door") && !hit) continue;
                    shownM.add(m);
                }
                info.setText(ed == null ? "Open a save first. " + all.size() + " rows: Lapis, Gil, Omniprism (stands in for unit prisms) and the awakening materials."
                        : shownM.size() + " of " + all.size() + " awakening materials. Counts come from the loaded save.");
                ad.notifyDataSetChanged();
            }
        };
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { fill[0].run(); }
        });
        matFill = fill[0];
        fill[0].run();
        return v;
    }

    // ------------------------------------------------------------------ story missions and summons

    private Runnable missFill, summFill, matFill;

    /** Loads the story and esper tables from the running game, then refreshes both tabs. */
    private void loadTables(final TextView info) {
        info.setText("Asking the game for its tables. The game must be open. Tap Install Script first. This can take a minute...");
        new Thread(new Runnable() {
            public void run() {
                String m;
                try { Tables.fetch(MainActivity.this); m = null; } catch (Exception e) { m = e.getMessage(); }
                final String err = m;
                ui.post(new Runnable() {
                    public void run() {
                        if (err != null) info.setText(err);
                        if (missFill != null) missFill.run();
                        if (summFill != null) summFill.run();
                    }
                });
            }
        }).start();
    }

    /** One list row: text on the left, one button on the right, a green or red background. */
    private LinearLayout actionRow(View.OnClickListener on) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(4), dp(6), dp(4));
        TextView info = text("");
        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button b = button("", on);
        b.setMinWidth(dp(92));
        row.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** Story missions: a list like the units list. Green = complete (all stars), red = not. A button on each row marks it. */
    private View buildMissions() {
        Tables.loadDungeons(this);
        if (!Tables.loaded()) Tables.loadCache(this);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        final TextView info = small("");
        v.addView(info);
        v.addView(button("Load tables from game", new View.OnClickListener() {
            public void onClick(View x) { loadTables(info); }
        }));
        final EditText q = new EditText(this);
        q.setHint("Search missions or dungeons");
        q.setSingleLine(true);
        v.addView(q);
        final List<Tables.Mission> shown = new ArrayList<Tables.Mission>();
        final Runnable[] fill = new Runnable[1];
        final ListView lv = new ListView(this);
        lv.setItemsCanFocus(true);
        final BaseAdapter ad = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int pos) { return shown.get(pos); }
            public long getItemId(int pos) { return pos; }
            public View getView(int pos, View old, ViewGroup parent) {
                LinearLayout row = (LinearLayout) old;
                if (row == null) {
                    row = actionRow(new View.OnClickListener() {
                        public void onClick(View x) {
                            Tables.Mission m = (Tables.Mission) x.getTag();
                            if (ed == null) { toast("Open a save first."); return; }
                            if (ed.missionDone(m.id)) { ed.incompleteMission(m); toast("Set " + m.id + " to incomplete."); }
                            else {
                                long lap = ed.completeMission(m);
                                toast("Completed " + m.id + (lap > 0 ? ", " + lap + " lapis added" : "") + "." + (ed.rewardNote.isEmpty() ? "" : " Reward:" + (ed.rewardNote.length() > 120 ? ed.rewardNote.substring(0, 120) + "..." : " " + ed.rewardNote)));
                            }
                            fill[0].run();
                            updateStatus();
                        }
                    });
                }
                Tables.Mission m = shown.get(pos);
                boolean d = ed != null && ed.missionDone(m.id);
                String stars = ed == null || m.ch.isEmpty() ? "" : "   \u2605 " + ed.starsDone(m) + "/" + m.ch.size();
                String mn = Tables.mname(m);
                ((TextView) row.getChildAt(0)).setText((mn.isEmpty() ? "Mission #" + m.number() : mn) + "\nPlace: " + Tables.place(m) + " #" + m.number()
                        + "\nRegion: " + Tables.region(m) + "\n" + m.id + stars);
                Button b = (Button) row.getChildAt(1);
                b.setText(d ? "Undo" : "Complete");
                b.setTag(m);
                row.setBackgroundColor(d ? 0xFF1B4D2C : 0xFF55202A);
                return row;
            }
        };
        lv.setAdapter(ad);
        v.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        fill[0] = new Runnable() {
            public void run() {
                String s = q.getText().toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                int nd = 0;
                for (Tables.Mission m : Tables.missions) {
                    if (ed != null && ed.missionDone(m.id)) nd++;
                    String line = Tables.mname(m) + " " + Tables.place(m) + " " + Tables.region(m) + " #" + m.number() + " " + m.id;
                    if (!s.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(s)) continue;
                    shown.add(m);
                }
                ad.notifyDataSetChanged();
                if (!Tables.loaded()) info.setText("No tables yet. Tap Install Script, open the game, then tap Load tables from game.");
                else if (ed == null) info.setText("Open a save first. " + Tables.missions.size() + " story missions known.");
                else info.setText(nd + " of " + Tables.missions.size() + " story missions complete. " + shown.size() + " shown. Green = complete, red = not." + (Tables.mNames.isEmpty() ? (Tables.dbg.isEmpty() ? " Tap Load tables from game again to get mission names." : " No mission names found. Text sample: " + Tables.dbg) : ""));
            }
        };
        missFill = fill[0];
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { fill[0].run(); }
        });
        LinearLayout r = row();
        addW(r, button("Complete all shown", new View.OnClickListener() {
            public void onClick(View x) {
                if (ed == null) { toast("Open a save first."); return; }
                if (shown.isEmpty()) { toast("Nothing shown. Load the tables first."); return; }
                final int n = shown.size();
                new Dlg(MainActivity.this).setTitle("Complete " + n + " missions?")
                        .setMessage("Marks the " + n + " shown missions complete with every star, opens their story switches, adds the star lapis and the first-clear rewards (units, items, gil, lapis) once per mission. "
                                + "Nothing is written until you apply to the game or save a copy.")
                        .setPositiveButton("Complete", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                long lap = 0;
                                StringBuilder rewards = new StringBuilder();
                                for (Tables.Mission m : new ArrayList<Tables.Mission>(shown)) { lap += ed.completeMission(m); if (!ed.rewardNote.isEmpty()) rewards.append(m.id).append(':').append(ed.rewardNote).append('\n'); }
                                toast(n + " missions complete" + (lap > 0 ? ", " + lap + " lapis added" : "") + ".");
                                if (rewards.length() > 0) new Dlg(MainActivity.this).setTitle("First-clear rewards added").setMessage(rewards.length() > 3000 ? rewards.substring(0, 3000) + "..." : rewards.toString()).setPositiveButton("OK", null).show();
                                fill[0].run(); updateStatus();
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        }));
        addW(r, button("Undo all shown", new View.OnClickListener() {
            public void onClick(View x) {
                if (ed == null) { toast("Open a save first."); return; }
                if (shown.isEmpty()) { toast("Nothing shown."); return; }
                final int n = shown.size();
                new Dlg(MainActivity.this).setTitle("Set " + n + " missions to incomplete?")
                        .setMessage("Removes the cleared mark and stars. Story switches and lapis you already got are left as they are.")
                        .setPositiveButton("Undo", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                for (Tables.Mission m : new ArrayList<Tables.Mission>(shown)) ed.incompleteMission(m);
                                toast(n + " missions set to incomplete.");
                                fill[0].run(); updateStatus();
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        }));
        v.addView(r);
        v.addView(withHelp(text("Story missions up to Kolobos Isle."), "Story missions of the first three areas, up to Kolobos Isle (the last is Shrine of Decay, number 7). "
                + "Tap Complete on a row to mark that mission cleared with every star, open its story switches and add the star lapis you had not received. "
                + "Search narrows the list, and Complete all shown does the whole list in one go. "
                + "Tap Load tables from game once (tap Install Script first and keep the game running). Then Apply to game; the game restarts."));
        return v;
    }

    /** Summons (espers): a list with an Add button on the right of each one you do not have. */
    private View buildSummon() {
        if (!Tables.loaded()) Tables.loadCache(this);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        final TextView info = small("");
        v.addView(info);
        v.addView(button("Load tables from game", new View.OnClickListener() {
            public void onClick(View x) { loadTables(info); }
        }));
        final EditText q = new EditText(this);
        q.setHint("Search summons");
        q.setSingleLine(true);
        v.addView(q);
        LinearLayout opt = row();
        final Spinner rar = spinner(java.util.Arrays.asList("Highest star", "1 star", "2 star", "3 star", "4 star", "5 star", "6 star"));
        addW(opt, rar);
        final CheckBox maxLv = new CheckBox(this);
        maxLv.setText("Max level");
        maxLv.setChecked(true);
        addW(opt, maxLv);
        v.addView(opt);
        LinearLayout opt2 = row();
        opt2.setGravity(Gravity.CENTER_VERTICAL);
        opt2.addView(small("SP  "));
        final EditText sp = new EditText(this);
        sp.setHint("SP (empty = level's own)");
        sp.setText("150");
        sp.setInputType(InputType.TYPE_CLASS_NUMBER);
        sp.setSingleLine(true);
        addW(opt2, sp);
        v.addView(opt2);
        final List<String> shown = new ArrayList<String>();
        final Runnable[] fill = new Runnable[1];
        final ListView lv = new ListView(this);
        lv.setItemsCanFocus(true);
        final BaseAdapter ad = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int pos) { return shown.get(pos); }
            public long getItemId(int pos) { return pos; }
            public View getView(int pos, View old, ViewGroup parent) {
                LinearLayout row = (LinearLayout) old;
                if (row == null) {
                    row = actionRow(new View.OnClickListener() {
                        public void onClick(View x) {
                            summonAdd((String) x.getTag(), rar.getSelectedItemPosition(), maxLv.isChecked(), sp.getText().toString().trim());
                            fill[0].run();
                        }
                    });
                }
                String id = shown.get(pos);
                String nm = Tables.names.get(id);
                if (nm == null || nm.length() == 0) nm = "Summon " + id;
                boolean o = ed != null && ed.ownsEsper(id);
                ((TextView) row.getChildAt(0)).setText(nm + "\n" + (o ? "Owned, level " + ed.espersOwnedLevel(id) : "Not owned") + "  (id " + id + ")");
                Button b = (Button) row.getChildAt(1);
                b.setTag(id);
                b.setText("Add");
                b.setVisibility(o ? View.GONE : View.VISIBLE);
                row.setBackgroundColor(o ? 0xFF1B4D2C : 0xFF55202A);
                return row;
            }
        };
        lv.setAdapter(ad);
        v.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        fill[0] = new Runnable() {
            public void run() {
                String s = q.getText().toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                int no = 0;
                for (String id : Tables.beasts.keySet()) {
                    String nm = Tables.names.get(id);
                    if (nm == null || nm.length() == 0) nm = "Summon " + id;
                    if (ed != null && ed.ownsEsper(id)) no++;
                    if (!s.isEmpty() && !(nm + " " + id).toLowerCase(Locale.ROOT).contains(s)) continue;
                    shown.add(id);
                }
                ad.notifyDataSetChanged();
                if (!Tables.loaded()) info.setText("No tables yet. Tap Install Script, open the game, then tap Load tables from game.");
                else if (ed == null) info.setText("Open a save first. " + Tables.beasts.size() + " summons known.");
                else info.setText(no + " of " + Tables.beasts.size() + " summons owned. Green = owned, red = not.");
            }
        };
        summFill = fill[0];
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { fill[0].run(); }
        });
        LinearLayout r = row();
        addW(r, button("Max level owned shown", new View.OnClickListener() {
            public void onClick(View x) {
                if (ed == null) { toast("Open a save first."); return; }
                int n = 0;
                String sps = sp.getText().toString().trim();
                for (String id : new ArrayList<String>(shown)) if (ed.ownsEsper(id)) { summonSet(id, rar.getSelectedItemPosition(), true, sps); n++; }
                toast(n == 0 ? "No owned summons shown." : "Max level on " + n + " owned summons.");
                fill[0].run(); updateStatus();
            }
        }));
        addW(r, button("Set SP owned shown", new View.OnClickListener() {
            public void onClick(View x) {
                if (ed == null) { toast("Open a save first."); return; }
                long n;
                try { n = Long.parseLong(sp.getText().toString().trim()); } catch (Exception e) { toast("Type the SP number first."); return; }
                int c = 0;
                for (String id : new ArrayList<String>(shown)) if (ed.ownsEsper(id)) { ed.setEsperCp(id, n); c++; }
                toast(c == 0 ? "No owned summons shown." : "SP set to " + n + " on " + c + " summons.");
                fill[0].run(); updateStatus();
            }
        }));
        v.addView(r);
        v.addView(withHelp(text("Add summons, set their level and SP."), "Tap Add on a summon you do not have. It is added at the star level in the first box (Highest star by default); "
                + "with Max level ticked it comes in at that star level's maximum. The SP box is the SP it gets (empty keeps what the level has earned). "
                + "The two buttons at the bottom apply to the owned summons that are shown, so search first to pick which. "
                + "Tap Load tables from game once, then Apply to game."));
        return v;
    }

    /** Picks the table row for a star choice: 0 = the highest star, else that star (null when the summon has none). */
    private Tables.Beast beastFor(String id, int rarPos) {
        List<Tables.Beast> l = Tables.beasts.get(id);
        if (l == null || l.isEmpty()) return null;
        if (rarPos == 0) return l.get(l.size() - 1);
        Tables.Beast b = null;
        for (Tables.Beast x : l) if (x.rar == rarPos) b = x;
        return b;
    }

    private void summonSet(String id, int rarPos, boolean max, String sps) {
        Tables.Beast b = beastFor(id, rarPos);
        if (b == null) return;
        ed.setEsper(b, max ? b.maxLv : 1);
        try { if (sps.length() > 0) ed.setEsperCp(id, Long.parseLong(sps)); } catch (NumberFormatException ignored) { }
    }

    private void summonAdd(String id, int rarPos, boolean max, String sps) {
        if (ed == null) { toast("Open a save first."); return; }
        if (beastFor(id, rarPos) == null) { toast("This summon has no " + rarPos + " star level. Pick another star level."); return; }
        summonSet(id, rarPos, max, sps);
        String nm = Tables.names.get(id);
        toast("Added " + (nm == null ? "summon " + id : nm) + (max ? " at max level" : " at level 1") + ". Apply to game to keep it.");
        updateStatus();
    }

    // ------------------------------------------------------------------ bestiary

    private Runnable bestFill;
    private final List<String[]> bestAll = new ArrayList<String[]>();

    /** The monster dictionary: tick monsters and unlock them, or unlock all. Needs an open save. */
    private View buildBestiary() {
        try {
            org.json.JSONArray a = new org.json.JSONArray(new String(readAll(getAssets().open("bestiary.json")), "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                String id = a.getJSONArray(i).getString(0), nm = a.getJSONArray(i).getString(1);
                bestAll.add(new String[]{id, nm, nm, "1"});
            }
        } catch (Exception e) {
            toast("Could not load the monster list: " + e.getMessage());
        }
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        final TextView info = text("Open a save first. Green monsters are unlocked in the save.");
        v.addView(info);
        final EditText q = new EditText(this);
        q.setHint("Search monsters");
        q.setSingleLine(true);
        v.addView(q);
        final List<String[]> shownB = new ArrayList<String[]>();
        final Set<String> pickedB = new HashSet<String>();
        final ListView lv = new ListView(this);
        lv.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        final Set<String> haveB = new HashSet<String>();
        final ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice, new ArrayList<String>()) {
            public View getView(int pos, View old, ViewGroup parent) {
                View v = super.getView(pos, old, parent);
                Ui.row(v);
                // soft dark green = unlocked, soft dark red = locked
                boolean on = pos < shownB.size() && haveB.contains(shownB.get(pos)[0]);
                v.setBackgroundColor(on ? 0xFF1B4D2C : 0xFF55202A);
                return v;
            }
        };
        lv.setAdapter(ad);
        final Runnable[] fillRef = new Runnable[1];
        fillRef[0] = new Runnable() {
            public void run() {
                String s = q.getText().toString().trim().toLowerCase(Locale.ROOT);
                Set<String> have = new HashSet<String>();
                if (ed != null) { if (ed.hasMonsterKey()) have.addAll(ed.monsterIds()); else for (String[] m : bestAll) have.add(m[0]); }
                haveB.clear();
                haveB.addAll(have);
                shownB.clear();
                List<String> rows = new ArrayList<String>();
                for (String[] m : bestAll) {
                    if (!s.isEmpty() && !m[2].toLowerCase(Locale.ROOT).contains(s) && !m[0].contains(s)) continue;
                    shownB.add(m);
                    rows.add((have.contains(m[0]) ? "\u2713 " : "    ") + m[1] + (m[3].equals("1") ? "" : " +" + (Integer.parseInt(m[3]) - 1)) + "  (" + m[0] + ")");
                }
                ad.clear();
                ad.addAll(rows);
                ad.notifyDataSetChanged();
                for (int i = 0; i < shownB.size(); i++) lv.setItemChecked(i, pickedB.contains(shownB.get(i)[0]));
                int unlocked = 0;
                for (String[] m : bestAll) if (have.contains(m[0])) unlocked++;
                info.setText(ed == null ? "Open a save first. Green monsters are unlocked in the save."
                        : unlocked + " of " + bestAll.size() + " monsters unlocked. " + pickedB.size() + " selected.");
            }
        };
        bestFill = fillRef[0];
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { fillRef[0].run(); }
        });
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                String mid = shownB.get(pos)[0];
                if (lv.isItemChecked(pos)) pickedB.add(mid); else pickedB.remove(mid);
                fillRef[0].run();
            }
        });
        LinearLayout r1 = row();
        addW(r1, button("Select shown", new View.OnClickListener() {
            public void onClick(View x) { for (String[] m : shownB) pickedB.add(m[0]); fillRef[0].run(); }
        }));
        addW(r1, button("Clear", new View.OnClickListener() {
            public void onClick(View x) { pickedB.clear(); fillRef[0].run(); }
        }));
        v.addView(r1);
        v.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout r2 = row();
        addW(r2, button("Unlock selected", new View.OnClickListener() {
            public void onClick(View x) {
                if (ed == null) { toast("Open a save first."); return; }
                if (pickedB.isEmpty()) { toast("Tick some monsters first."); return; }
                int n = ed.unlockMonsters(pickedB);
                toast("Unlocked " + n + " monster" + (n == 1 ? "" : "s") + ". Save or apply to keep it.");
                fillRef[0].run();
                updateStatus();
            }
        }));
        addW(r2, button("Lock selected", new View.OnClickListener() {
            public void onClick(View x) {
                if (ed == null) { toast("Open a save first."); return; }
                if (!ed.hasMonsterKey()) { List<String> all0 = new ArrayList<String>(); for (String[] m : bestAll) all0.add(m[0]); ed.unlockMonsters(all0); }
                int n = ed.lockMonsters(pickedB);
                toast("Locked " + n + " monster" + (n == 1 ? "" : "s") + ". Save or apply to keep it.");
                fillRef[0].run();
                updateStatus();
            }
        }));
        v.addView(r2);
        LinearLayout r3 = row();
        addW(r3, button("Unlock all", new View.OnClickListener() {
            public void onClick(View x) {
                if (ed == null) { toast("Open a save first."); return; }
                List<String> all = new ArrayList<String>();
                for (String[] m : bestAll) all.add(m[0]);
                int n = ed.unlockMonsters(all);
                toast("Unlocked " + n + " more monsters. Save or apply to keep it.");
                fillRef[0].run();
                updateStatus();
            }
        }));
        addW(r3, button("Lock all", new View.OnClickListener() {
            public void onClick(View x) {
                if (ed == null) { toast("Open a save first."); return; }
                final int have = ed.hasMonsterKey() ? ed.monsterIds().size() : bestAll.size();
                if (ed.hasMonsterKey() && have == 0) { toast("No monsters are unlocked."); return; }
                new Dlg(MainActivity.this).setTitle("Lock all monsters?")
                        .setMessage("This removes all " + have + " monsters from the save's Bestiary, so the game's Bestiary starts empty again. "
                                + "Nothing is written until you save or apply.")
                        .setPositiveButton("Lock all", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                int n = have;
                                ed.clearMonsters();
                                toast("Locked " + n + " monsters. Save or apply to keep it.");
                                fillRef[0].run();
                                updateStatus();
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        }));
        v.addView(r3);
        v.addView(withHelp(text("Needs the Game Patch switch \"Unlock Monster Guide\", then tap Install Script."), "Pick which monsters the game's Monster Guide shows. "
                + "Tick monsters, then Unlock selected or Lock selected, or use Unlock all and Lock all. "
                + "Then use Apply to game (tap Install Script first) so the choice reaches the game. "
                + "A save that was never edited here shows every monster in the game."));
        return v;
    }

    private View buildEditUnits() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        search = new EditText(this);
        search.setHint("Search units");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { refresh(); }
        });
        LinearLayout sr = row();
        sr.setGravity(Gravity.CENTER_VERTICAL);
        addW(sr, search);
        sr.addView(filterButton(new View.OnClickListener() {
            public void onClick(View x) { sortPopup(); }
        }), miniLp());
        v.addView(sr);
        LinearLayout r2 = row();
        addW(r2, button("Select shown", new View.OnClickListener() {
            public void onClick(View x) { selectShown(true); }
        }));
        addW(r2, button("Clear", new View.OnClickListener() {
            public void onClick(View x) { selectShown(false); }
        }));
        v.addView(r2);
        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setItemsCanFocus(true);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<String>()) {
            public View getView(final int pos, View old, ViewGroup parent) {
                CheckRow r = old instanceof CheckRow ? (CheckRow) old : new CheckRow(MainActivity.this);
                r.fill(MainActivity.this, pos);
                r.del.setVisibility(story(pos) ? View.GONE : View.VISIBLE);
                r.del.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View x) { confirmDelete(pos); }
                });
                return r;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                String iid = Editor.str(ed.roster.get(shown.get(pos)).get("instance_id"));
                if (list.isItemChecked(pos)) picked.add(iid); else picked.remove(iid);
                updateStatus();
            }
        });
        v.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        maxLb = new CheckBox(this);
        maxLb.setText("Max level also maxes Limit Burst");
        maxLb.setChecked(true);
        v.addView(maxLb);
        LinearLayout r3 = row();
        addW(r3, button("Max level", new View.OnClickListener() {
            public void onClick(View x) { run(0); }
        }));
        addW(r3, button("100% TMR + reward", new View.OnClickListener() {
            public void onClick(View x) { run(1); }
        }));
        v.addView(r3);
        LinearLayout r4 = row();
        starPick = new Spinner(this);
        List<String> st = new ArrayList<String>();
        for (int i = 1; i <= 7; i++) st.add(i + "★");
        st.add("Max");
        ArrayAdapter<String> sa = Ui.adapter(this, android.R.layout.simple_spinner_item, st);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        starPick.setAdapter(sa);
        starPick.setSelection(st.size() - 1);
        addW(r4, starPick);
        addW(r4, button("Set stars", new View.OnClickListener() {
            public void onClick(View x) { run(2); }
        }));
        r4.setGravity(Gravity.CENTER_VERTICAL);
        r4.addView(helpIcon("To set stars: use the dropdown on the left to pick the star (1★ to 7★ or Max), then tap Set stars."), helpLp());
        v.addView(r4);
        return v;
    }

    private void openSave() {
        if (ed != null && ed.dirty) {
            new Dlg(this).setTitle("Unsaved changes")
                    .setMessage("Your changes are not saved yet. Open another file anyway?")
                    .setPositiveButton("Open", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { pickSave(); }
                    }).setNegativeButton("Cancel", null).show();
        } else {
            pickSave();
        }
    }

    private void pickSave() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, OPEN_SAVE);
    }

    private String displayName(Uri u) {
        Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return "save.json";
    }

    private final Runnable syncCopy = new Runnable() {
        public void run() {
            if (ed == null || Sync.tree(MainActivity.this) == null) return;
            final String json = ed.gambitExport();
            new Thread(new Runnable() {
                public void run() {
                    try { Sync.putFile(MainActivity.this, "ffbe_gambit_presets.json", "application/json", json.getBytes("UTF-8")); } catch (Exception ignored) { }
                }
            }).start();
        }
    };

    /** The app keeps its own copy of the presets (survives updates and a game save that drops them) and a copy in the sync folder. */
    private void keepPresets() {
        if (ed == null) return;
        try {
            FileOutputStream o = new FileOutputStream(new File(getFilesDir(), "gambit_presets.json"));
            o.write(Json.write(ed.presetsData()).getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) { }
        SpeedService.poke();     // the running game gets the new rules at once, no restart
        ui.removeCallbacks(syncCopy);
        ui.postDelayed(syncCopy, 4000);
    }

    /** Merges the app's own preset copy into a save that was just opened. */
    private void mergePresets() {
        if (ed == null) return;
        try {
            File f = new File(getFilesDir(), "gambit_presets.json");
            if (f.exists()) {
                Object o = Json.parse(new String(readAll(new FileInputStream(f)), "UTF-8"));
                if (o instanceof Map) ed.presetsAdopt((Map<String, Object>) o);
            }
        } catch (Exception ignored) { }
        keepPresets();
    }

    private void loadSave(Uri u) {
        try {
            String txt = new String(readAll(getContentResolver().openInputStream(u)), "UTF-8");
            ed = new Editor(db, txt);
            mergePresets();
            saveName = displayName(u);
            picked.clear();
            search.setText("");
            refresh();
            if (unitsFill != null) unitsFill.run();
            if (itemsFill != null) itemsFill.run();
            if (bestFill != null) bestFill.run();
            if (missFill != null) missFill.run();
            if (summFill != null) summFill.run();
            if (gambit != null) gambit.refresh();
            toast("Loaded " + ed.roster.size() + " units from " + saveName);
        } catch (Exception e) {
            alert("Could not open the save", String.valueOf(e.getMessage()));
        }
    }

    /** One row of the Edit Units list: check box, portrait, name, banner, party mark, gear mark, delete button. */
    private static class CheckRow extends LinearLayout implements android.widget.Checkable {
        final CheckBox box;
        final ImageView pic, gear;
        final TextView label, sub, ban;
        final ImageView party;
        final Button del;
        CheckRow(MainActivity a) {
            super(a);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(a.dp(8), a.dp(4), a.dp(4), a.dp(4));
            box = new CheckBox(a);
            box.setClickable(false);
            box.setFocusable(false);
            addView(box);
            pic = new ImageView(a);
            pic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(pic, new LinearLayout.LayoutParams(a.dp(48), a.dp(48)));
            LinearLayout mid = new LinearLayout(a);
            mid.setOrientation(VERTICAL);
            mid.setPadding(a.dp(8), 0, a.dp(4), 0);
            label = new TextView(a);
            label.setTextSize(16);
            label.setTextColor(Ui.TEXT);
            mid.addView(label);
            sub = new TextView(a);
            sub.setTextSize(13);
            sub.setTextColor(Ui.CREAM);
            mid.addView(sub);
            ban = new TextView(a);
            ban.setTextSize(11);
            ban.setTextColor(Ui.DIM);
            ban.setSingleLine(true);
            ban.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(ban);
            addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout rc = new LinearLayout(a);
            rc.setOrientation(VERTICAL);
            rc.setGravity(Gravity.RIGHT);
            party = new ImageView(a);
            party.setImageDrawable(Ui.partyIcon(a, 22));
            party.setContentDescription("In party");
            rc.addView(party, new LinearLayout.LayoutParams(a.dp(22), a.dp(22)));
            gear = new ImageView(a);
            gear.setImageDrawable(Ui.gearIcon(a, 18));
            rc.addView(gear, new LinearLayout.LayoutParams(a.dp(18), a.dp(18)));
            addView(rc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            del = a.button("Remove", null);
            del.setMinWidth(a.dp(72));
            del.setFocusable(false);
            del.setBackground(Ui.redBg(a));
            addView(del);
        }
        void fill(MainActivity a, int pos) {
            Map<String, Object> u = a.ed.roster.get(a.shown.get(pos));
            String mid = Editor.str(u.get("master_id"));
            Db.Unit d = a.ed.db.byMaster.get(mid);
            label.setText(a.ed.name(u));
            sub.setText(Editor.star(mid) + "\u2605  Lv " + Editor.num(u.get("level")) + "  TMR " + a.ed.tmrPercent(u) + "%");
            String bn = "";
            if (d != null && d.banner >= 0 && d.banner < a.ed.db.banners.size()) bn = a.ed.db.banners.get(d.banner).label();
            ban.setText(bn);
            ban.setVisibility(bn.isEmpty() ? View.GONE : View.VISIBLE);
            android.graphics.Bitmap bm = d == null ? null : Icons.unit(d.base);
            pic.setImageBitmap(bm);
            party.setVisibility(a.ed.partyIds().contains(Editor.str(u.get("instance_id"))) ? View.VISIBLE : View.INVISIBLE);
            gear.setVisibility(a.ed.hasGear(u) ? View.VISIBLE : View.INVISIBLE);
        }
        public void setChecked(boolean c) { box.setChecked(c); }
        public boolean isChecked() { return box.isChecked(); }
        public void toggle() { box.toggle(); }
    }

    /** Main story cast (Rain, Lasswell, Fina, Nichol, Lid, Sakura, Jake and their 2-6 star forms): no delete. */
    private boolean story(int pos) {
        if (ed == null || pos < 0 || pos >= shown.size()) return false;
        String m = Editor.str(ed.roster.get(shown.get(pos)).get("master_id"));
        if (m.length() != 9 || !m.startsWith("100000")) return false;
        char c = m.charAt(6);
        int tail = Integer.parseInt(m.substring(7));
        return c >= '1' && c <= '7' && tail <= 6;
    }

    /** Asks first, then removes the unit from the save (and from any party). */
    private void confirmDelete(int pos) {
        if (ed == null || pos < 0 || pos >= shown.size() || story(pos)) return;
        final Map<String, Object> u = ed.roster.get(shown.get(pos));
        final String iid = Editor.str(u.get("instance_id"));
        final String label = ed.label(u);
        boolean inParty = ed.partyIds().contains(iid);
        new Dlg(this).setTitle("Remove this unit?")
                .setMessage(label + (inParty ? "\n\nThis unit is in a party. It will be taken out of the party too." : "")
                        + "\n\nIt is taken out of the save. Gear it wears stays in your items. Nothing is written until you save.")
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (ed.deleteUnit(iid)) {
                            picked.remove(iid);
                            refresh();
                            toast("Removed " + label);
                        }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void sortPopup() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(4));
        TextView t1 = text("Sort by");
        t1.setTextColor(Ui.GOLD);
        box.addView(t1);
        final android.widget.RadioGroup g1 = new android.widget.RadioGroup(this);
        String[] ks = {"Save order", "Name", "Rarity", "Level"};
        for (int i = 0; i < ks.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(ks[i]);
            rb.setId(100 + i);
            g1.addView(rb);
        }
        g1.check(100 + sortKey);
        box.addView(g1);
        TextView t2 = text("Order");
        t2.setTextColor(Ui.GOLD);
        box.addView(t2);
        final android.widget.RadioGroup g2 = new android.widget.RadioGroup(this);
        String[] os = {"Ascending (A-Z, low to high)", "Descending (Z-A, high to low)"};
        for (int i = 0; i < os.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(os[i]);
            rb.setId(200 + i);
            g2.addView(rb);
        }
        g2.check(sortDown ? 201 : 200);
        box.addView(g2);
        new Dlg(this).setTitle("Sort").setView(box)
                .setPositiveButton("Sort", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        sortKey = g1.getCheckedRadioButtonId() - 100;
                        sortDown = g2.getCheckedRadioButtonId() == 201;
                        refresh();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void refresh() {
        if (ed == null) return;
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        Set<String> party = ed.partyIds();
        shown.clear();
        List<String> rows = new ArrayList<String>();
        for (int i = 0; i < ed.roster.size(); i++) {
            Map<String, Object> u = ed.roster.get(i);
            String label = ed.label(u);
            if (!q.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(i);
        }
        if (sortKey > 0) {
            final int k = sortKey;
            final int sg = sortDown ? -1 : 1;
            java.util.Collections.sort(shown, new java.util.Comparator<Integer>() {
                public int compare(Integer x, Integer y) {
                    Map<String, Object> a = ed.roster.get(x), b = ed.roster.get(y);
                    int c;
                    if (k == 1) c = ed.name(a).compareToIgnoreCase(ed.name(b));
                    else if (k == 2) c = Integer.compare(Editor.star(Editor.str(a.get("master_id"))), Editor.star(Editor.str(b.get("master_id"))));
                    else c = Long.compare(Editor.num(a.get("level")), Editor.num(b.get("level")));
                    if (c == 0) c = ed.name(a).compareToIgnoreCase(ed.name(b));
                    return c * sg;
                }
            });
        }
        for (int i = 0; i < shown.size(); i++) rows.add(ed.label(ed.roster.get(shown.get(i))));
        adapter.clear();
        adapter.addAll(rows);
        adapter.notifyDataSetChanged();
        for (int p = 0; p < shown.size(); p++)
            list.setItemChecked(p, picked.contains(Editor.str(ed.roster.get(shown.get(p)).get("instance_id"))));
        updateStatus();
    }

    private void updateStatus() {
        if (ed == null) return;
        status.setText(saveName + ": " + ed.roster.size() + " units, " + picked.size() + " selected"
                + (ed.dirty ? ". Changed, not saved yet." : "."));
    }

    private void selectShown(boolean on) {
        if (ed == null) return;
        for (int p = 0; p < shown.size(); p++) {
            String iid = Editor.str(ed.roster.get(shown.get(p)).get("instance_id"));
            if (on) picked.add(iid); else picked.remove(iid);
            list.setItemChecked(p, on);
        }
        updateStatus();
    }

    /** op 0 = max level, 1 = TMR, 2 = stars. No selection = ask to use every unit. */
    private void run(final int op) {
        if (ed == null) { toast("Open a save first."); return; }
        if (picked.isEmpty()) {
            new Dlg(this).setTitle("No units selected")
                    .setMessage("Apply this to all " + ed.roster.size() + " owned units?")
                    .setPositiveButton("All units", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { apply(op, true); }
                    }).setNegativeButton("Cancel", null).show();
        } else {
            apply(op, false);
        }
    }

    private void apply(int op, boolean all) {
        int changed = 0, same = 0, unknown = 0;
        int want = starPick.getSelectedItemPosition() + 1;   // 8 = Max
        for (Map<String, Object> u : ed.roster) {
            if (!all && !picked.contains(Editor.str(u.get("instance_id")))) continue;
            if (op == 0) {
                if (ed.maxLevel(u, maxLb.isChecked())) changed++; else same++;
            } else if (op == 1) {
                int r = ed.tmr(u);
                if (r == Editor.TMR_DONE) changed++; else if (r == Editor.TMR_ALREADY) same++; else unknown++;
            } else {
                String before = Editor.str(u.get("master_id"));
                int r = ed.setStars(u, want > 7 ? 7 : want);
                if (r < 0) unknown++; else if (before.equals(Editor.str(u.get("master_id")))) same++; else changed++;
            }
        }
        refresh();
        String what = op == 0 ? "Max level" : op == 1 ? "100% TMR" : "Stars";
        String msg = what + ": " + changed + " changed, " + same + " already done";
        if (unknown > 0) msg += ", " + unknown + " skipped (not in the unit list)";
        if (op == 2 && want <= 7) msg += ". Units that can't be " + want + "★ were set to the closest star they have.";
        if (op == 1) msg += ". Each new TMR was put in your inventory.";
        alert("Done", msg + "\n\nTap \"Save edited copy\" to write the file.");
    }

    private static final String[] SLOTS = {"All kinds", "Weapon", "Shield", "Headgear", "Chest", "Accessory", "Materia"};

    private Spinner spinner(List<String> items) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> a = Ui.adapter(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        return sp;
    }

    private void onPick(Spinner sp, final Runnable r) {
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { r.run(); }
            public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    /** Blue and gold popup holding content (the content is detached again when it closes, so its state survives). */
    private void popup(String title, final View content) {
        final ScrollView sv = new ScrollView(this);
        final LinearLayout pad = new LinearLayout(this);
        pad.setPadding(dp(16), dp(8), dp(16), dp(8));
        pad.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(pad);
        Ui.apply(content);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(18);
        t.setTypeface(Typeface.SERIF, Typeface.BOLD);
        t.setTextColor(Ui.GOLD);
        t.setPadding(dp(20), dp(16), dp(20), dp(4));
        AlertDialog d = new Dlg(this).setCustomTitle(t).setView(sv).setPositiveButton("Done", null).create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface x) { pad.removeAllViews(); }
        });
        d.show();
        Dlg.style(d);
    }

    /** Small round "?" that shows a note when tapped. */
    private TextView helpIcon(final String note) {
        TextView q = new TextView(this);
        q.setText("?");
        q.setTextSize(14);
        q.setTypeface(Typeface.DEFAULT_BOLD);
        q.setGravity(Gravity.CENTER);
        q.setTextColor(Ui.GOLD);
        q.setTag("keep");
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(Ui.NAVY);
        g.setStroke(Math.max(1, dp(1)), Ui.GOLD);
        q.setBackground(g);
        q.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                TextView n = new TextView(MainActivity.this);
                n.setText(note);
                n.setTextSize(15);
                n.setTextColor(Ui.TEXT);
                popup("Note", n);
            }
        });
        return q;
    }

    private LinearLayout.LayoutParams helpLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(2);
        return lp;
    }

    /** v on the left (takes the free width), a "?" with the note on the right. */
    private LinearLayout withHelp(View v, String note) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(helpIcon(note), helpLp());
        return r;
    }

    /** Small rectangular icon button, same look as the ? button but wider than tall. */
    private View miniButton(View icon, String desc, View.OnClickListener on) {
        FrameLayout f = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Ui.NAVY);
        g.setCornerRadius(dp(6));
        g.setStroke(Math.max(1, dp(1)), Ui.GOLD);
        f.setBackground(g);
        f.setContentDescription(desc);
        f.setOnClickListener(on);
        f.addView(icon, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return f;
    }

    private LinearLayout.LayoutParams miniLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(26));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(2);
        return lp;
    }

    private View filterButton(View.OnClickListener on) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(Ui.funnel(this, 14));
        return miniButton(iv, "Filter", on);
    }

    private View gearButton(View.OnClickListener on) {
        TextView t = new TextView(this);
        t.setText("\u2699");
        t.setTextSize(14);
        t.setTextColor(Ui.GOLD);
        t.setTag("keep");
        t.setIncludeFontPadding(false);
        return miniButton(t, "Settings", on);
    }

    /** Empty stretch that pushes what follows to the right edge of a row. */
    private View spacer() { return new View(this); }

    private TextView small(String s) {
        TextView t = text(s);
        t.setTextSize(12);
        return t;
    }

    /** Collapsible banner range filter (labels show the season) with a Hide No Banner tick, shared by Add Units and TMR/Equipment. */
    private final class BannerFilter {
        final LinearLayout view;
        final Spinner bFrom, bTo;
        final CheckBox hideNone, hideEst, hideOwned;

        BannerFilter(final String title, final String ownedLabel, final Runnable fill) {
            view = new LinearLayout(MainActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            final LinearLayout body = view;
            TextView ttl = text(title);
            ttl.setTypeface(Typeface.SERIF, Typeface.BOLD);
            ttl.setTextColor(Ui.GOLD);
            ttl.setTag("keep");
            body.addView(withHelp(ttl, "From banner and to banner. S1 to S4 is in each label."));
            List<String> bl = new ArrayList<String>();
            for (Db.Banner x : db.banners) bl.add(x.label());
            bFrom = spinner(bl);
            bTo = spinner(bl);
            if (!bl.isEmpty()) bTo.setSelection(bl.size() - 1);
            body.addView(bFrom);
            body.addView(bTo);
            hideNone = new CheckBox(MainActivity.this);
            hideNone.setText("Hide No Banner");
            body.addView(withHelp(hideNone, "Things that were never on a banner (shop, quest, craft, story or event units) show unless this is ticked."));
            hideEst = new CheckBox(MainActivity.this);
            hideEst.setText("Hide estimated banners");
            body.addView(withHelp(hideEst, "Estimated (~): the game has no banner for it, so the banner is guessed from release order. Can be a few weeks off."));
            hideOwned = new CheckBox(MainActivity.this);
            hideOwned.setText(ownedLabel);
            body.addView(hideOwned);
            hideOwned.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) { fill.run(); }
            });
            hideEst.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) { fill.run(); }
            });
            onPick(bFrom, fill);
            onPick(bTo, fill);
            hideNone.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) { fill.run(); }
            });
        }

        /** True when a thing whose first banner is idx (-1 = none) passes the filter. */
        boolean pass(int idx, boolean est) {
            if (est && hideEst.isChecked()) return false;
            return pass(idx);
        }

        boolean pass(int idx) {
            if (idx < 0 || idx >= db.banners.size()) return !hideNone.isChecked();
            int b1 = bFrom.getSelectedItemPosition(), b2 = bTo.getSelectedItemPosition();
            if (b1 > b2) { int t = b1; b1 = b2; b2 = t; }
            return idx >= b1 && idx <= b2;
        }
    }

    // ------------------------------------------------------------------ add units section

    private static final String[] CAPS = {"Awakening open: up to 7★", "Awakening open: up to 6★", "Awakening open: up to 5★"};

    /** Highest star the player says is open in their game (the game gates 6★/7★ by banner). */
    private int capStar(Spinner cap) { return 7 - cap.getSelectedItemPosition(); }

    /** Top star a unit can be added at: its own max, lowered to the open cap but never below its base. */
    private static int top(Db.Unit d, int cap) { return Math.max(d.minStar(), Math.min(d.maxStar(), cap)); }

    private void setIcon(ImageView v, android.graphics.Bitmap b) {
        v.setVisibility(b == null ? View.GONE : View.VISIBLE);
        v.setImageBitmap(b);
    }

    private View buildAddUnits() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(small("Tap Add to put one new copy of the unit in your save. Adjust settings in gear button. "
                + "Unticked, unit comes in like a fresh summon: base star, level 1, no gear, TMR 0%."));
        final EditText q = new EditText(this);
        q.setHint("Search units by name");
        q.setSingleLine(true);
        box.addView(q);

        final LinearLayout opt = new LinearLayout(this);
        opt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r1 = row();
        final CheckBox maxLv = new CheckBox(this);
        maxLv.setText("Add Max Level");
        addW(r1, maxLv);
        final CheckBox maxAw = new CheckBox(this);
        maxAw.setText("Add Max Awakening");
        addW(r1, maxAw);
        opt.addView(r1);
        final CheckBox maxTmr = new CheckBox(this);
        maxTmr.setText("Add Max TMR (100% + TMR item into inventory)");
        opt.addView(maxTmr);

        LinearLayout r2 = row();
        List<String> bases = new ArrayList<String>();
        bases.add("Any base ★");
        for (int i = 1; i <= 7; i++) bases.add("Base " + i + "★");
        final Spinner baseF = spinner(bases);
        addW(r2, baseF);
        List<String> tops = new ArrayList<String>();
        tops.add("Any top ★");
        for (int i = 1; i <= 7; i++) tops.add("Top " + i + "★");
        final Spinner topF = spinner(tops);
        addW(r2, topF);
        final LinearLayout awk = new LinearLayout(this);
        awk.setOrientation(LinearLayout.VERTICAL);
        awk.addView(r2);

        final android.content.SharedPreferences prefs = getSharedPreferences("editor", MODE_PRIVATE);
        LinearLayout r3 = row();
        List<String> caps = new ArrayList<String>();
        for (String c : CAPS) caps.add(c);
        final Spinner cap = spinner(caps);
        cap.setSelection(Math.max(0, Math.min(CAPS.length - 1, prefs.getInt("awakeCap", 0))));
        addW(r3, cap);
        final CheckBox hideOdd = new CheckBox(this);
        hideOdd.setText("Hide moogles / cactuars");
        hideOdd.setChecked(true);
        opt.addView(hideOdd);
        awk.addView(withHelp(r3, "\"Awakening open\": choose the highest star your game allows right now. Units won't be added above it, and rows show what is locked."));

        final Runnable[] fillRef = new Runnable[1];
        final BannerFilter bf = new BannerFilter("Banner range (the unit's first banner)", "Hide owned units (already in this save)", new Runnable() {
            public void run() { if (fillRef[0] != null) fillRef[0].run(); }
        });
        final LinearLayout filterBox = new LinearLayout(this);
        filterBox.setOrientation(LinearLayout.VERTICAL);
        filterBox.addView(awk);
        filterBox.addView(bf.view);
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1));
        bar.addView(gearButton(new View.OnClickListener() {
            public void onClick(View x) { popup("Add options", opt); }
        }), miniLp());
        bar.addView(filterButton(new View.OnClickListener() {
            public void onClick(View x) { popup("Filter", filterBox); }
        }), miniLp());
        box.addView(bar);
        final TextView found = small("");
        box.addView(found);

        final List<Db.Unit> all = new ArrayList<Db.Unit>(db.units);
        java.util.Collections.sort(all, new java.util.Comparator<Db.Unit>() {
            public int compare(Db.Unit a, Db.Unit b) {
                int c = a.name.compareToIgnoreCase(b.name);
                return c != 0 ? c : a.base.compareTo(b.base);
            }
        });
        final List<Db.Unit> shownUnits = new ArrayList<Db.Unit>();
        final ListView lv = new ListView(this);
        lv.setItemsCanFocus(true);
        unitsAdapter = new BaseAdapter() {
            public int getCount() { return shownUnits.size(); }
            public Object getItem(int pos) { return shownUnits.get(pos); }
            public long getItemId(int pos) { return pos; }
            public View getView(int pos, View old, ViewGroup parent) {
                LinearLayout row = (LinearLayout) old;
                if (row == null) {
                    row = row();
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(4), 0, dp(4));
                    ImageView ic = new ImageView(MainActivity.this);
                    ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    ic.setPadding(0, 0, dp(8), 0);
                    row.addView(ic, new LinearLayout.LayoutParams(dp(64), dp(40)));
                    TextView info = text("");
                    row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    Button add = button("Add", new View.OnClickListener() {
                        public void onClick(View x) {
                            addUnit((Db.Unit) x.getTag(), capStar(cap), maxAw.isChecked(), maxLv.isChecked(), maxTmr.isChecked());
                        }
                    });
                    add.setMinWidth(dp(72));
                    LinearLayout col = new LinearLayout(MainActivity.this);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setGravity(Gravity.CENTER_HORIZONTAL);
                    TextView have = small("");
                    have.setGravity(Gravity.CENTER);
                    col.addView(have, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    col.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    row.addView(col, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    Ui.apply(row);
                }
                Db.Unit d = shownUnits.get(pos);
                int c = capStar(cap), t = top(d, c);
                String range = d.minStar() + "★ ~ " + t + "★" + (t < d.maxStar() ? ", " + d.maxStar() + "★ locked" : "");
                String when = d.banner >= 0 && d.banner < db.banners.size() ? (d.est ? "~" : "") + db.banners.get(d.banner).label() + (d.est ? " (est.)" : "") : "no banner";
                ((TextView) row.getChildAt(1)).setText(d.name + " (" + range + ")\n" + when
                        );
                LinearLayout col = (LinearLayout) row.getChildAt(2);
                ((TextView) col.getChildAt(0)).setText("Have " + (ed == null ? "-" : String.valueOf(ed.ownedCount(d))));
                col.getChildAt(1).setTag(d);
                setIcon((ImageView) row.getChildAt(0), Icons.unit(d.base));
                return row;
            }
        };
        lv.setAdapter(unitsAdapter);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        final Runnable fill = new Runnable() {
            public void run() {
                String s = q.getText().toString().trim().toLowerCase(Locale.ROOT);
                int base = baseF.getSelectedItemPosition(), want = topF.getSelectedItemPosition(), c = capStar(cap);
                shownUnits.clear();
                for (Db.Unit d : all) {
                    if (hideOdd.isChecked() && d.base.startsWith("9")) continue;
                    if (base > 0 && d.minStar() != base) continue;
                    if (want > 0 && top(d, c) != want) continue;
                    if (!bf.pass(d.banner, d.est)) continue;
                    if (bf.hideOwned.isChecked() && ed != null && ed.ownedCount(d) > 0) continue;
                    if (!s.isEmpty() && !d.name.toLowerCase(Locale.ROOT).contains(s)) continue;
                    shownUnits.add(d);
                }
                found.setText(shownUnits.size() + " units" + (ed == null ? ". Open a save to add them." : ""));
                unitsAdapter.notifyDataSetChanged();
            }
        };
        fillRef[0] = fill;
        unitsFill = fill;
        onPick(baseF, fill);
        onPick(topF, fill);
        onPick(cap, new Runnable() {
            public void run() {
                prefs.edit().putInt("awakeCap", cap.getSelectedItemPosition()).apply();
                fill.run();
            }
        });
        hideOdd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { fill.run(); }
        });
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence x, int a, int b, int c) { }
            public void onTextChanged(CharSequence x, int a, int b, int c) { }
            public void afterTextChanged(Editable x) { fill.run(); }
        });
        return box;
    }

    /** Adds one copy: base star, or the highest open star; then optional max level (+LB) and 100% TMR + item. */
    private void addUnit(Db.Unit d, int cap, boolean maxAw, boolean maxLv, boolean maxTmr) {
        if (ed == null) { toast("Open a save first."); return; }
        Map<String, Object> u = ed.addUnit(d, maxAw ? top(d, cap) : d.minStar());
        if (maxLv) ed.maxLevel(u, true);
        String extra = "";
        if (maxTmr) {
            int r = ed.tmr(u);
            extra = r == Editor.TMR_DONE ? " TMR 100%, TMR item added." : r == Editor.TMR_UNKNOWN ? " No TMR for this unit in the data, TMR left at 0%." : " TMR was already done.";
        }
        toast("Added " + ed.label(u) + "." + extra + " You now have " + ed.ownedCount(d) + ".");
        unitsAdapter.notifyDataSetChanged();
        refresh();
    }

    private void removeItem(final Db.Item it, EditText count, final Runnable refill) {
        if (ed == null) { toast("Open a save first."); return; }
        long n0;
        try { n0 = Long.parseLong(count.getText().toString().trim()); } catch (NumberFormatException e) { n0 = 1; }
        if (n0 < 1) n0 = 1;
        final long n = n0;
        final long have = ed.owned(it.kind, it.id), worn = ed.worn(it.kind, it.id), spare = ed.spare(it.kind, it.id);
        if (Math.max(have, worn) <= 0) { toast("You have none of " + it.name + "."); return; }
        List<String> who = ed.wearerNames(it.kind, it.id);
        String names = who.size() > 6 ? join(who.subList(0, 6)) + " and " + (who.size() - 6) + " more" : join(who);
        final Runnable done = new Runnable() {
            public void run() { if (refill != null) refill.run(); updateStatus(); }
        };
        AlertDialog.Builder b = new Dlg(this).setTitle("Remove " + it.name + "?").setNegativeButton("Cancel", null);
        String why = "Own " + Math.max(have, worn) + ", worn by units " + worn + (worn > 0 ? " (" + names + ")" : "") + ", spare " + spare + ".\n\n";
        if (n <= spare) {
            b.setMessage(why + "Removes " + n + " spare. Worn copies stay equipped.")
                    .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            long[] r = ed.remove(it.kind, it.id, n, false);
                            toast("Removed " + r[0] + " x " + it.name);
                            done.run();
                        }
                    });
        } else {
            final long over = Math.min(n, Math.max(have, worn)) - spare;
            b.setMessage(why + "You asked for " + n + ", more than the spare copies. The game counts worn copies as owned, "
                    + "so a worn copy cannot just be deleted: it comes back at once. To remove it, it has to be taken off the unit first.")
                    .setPositiveButton("Unequip and remove " + (spare + over), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            long[] r = ed.remove(it.kind, it.id, n, true);
                            toast("Removed " + r[0] + " x " + it.name + ", " + r[1] + " taken off units");
                            done.run();
                        }
                    });
            if (spare > 0) b.setNeutralButton("Spare only (" + spare + ")", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    long[] r = ed.remove(it.kind, it.id, n, false);
                    toast("Removed " + r[0] + " x " + it.name);
                    done.run();
                }
            });
        }
        b.show();
    }

    private void unclaimTmr(final Db.Item it, final Runnable refill) {
        if (ed == null) { toast("Open a save first."); return; }
        List<Map<String, Object>> who = ed.tmrClaimers(it.kind, it.id);
        if (who.isEmpty()) { toast("No unit in this save has claimed " + it.name + "."); return; }
        List<String> names = new ArrayList<String>();
        for (Map<String, Object> u : who) names.add(ed.name(u) + " (TMR " + ed.tmrPercent(u) + "%)");
        new Dlg(this).setTitle("Unclaim " + it.name + "?")
                .setMessage("Sets TMR to 99% and clears the claim on: " + join(names) + ".\n\n"
                        + "You can then claim it again in the game and the item is paid out again. "
                        + "This does not remove the item you already have. Use Remove for that, or you will end up with a spare.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Unclaim", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int n = ed.unclaimTmr(it.kind, it.id);
                        toast("Unclaimed on " + n + " unit" + (n == 1 ? "" : "s") + ". TMR is now 99%.");
                        if (refill != null) refill.run();
                        updateStatus();
                        if (unitsAdapter != null) unitsAdapter.notifyDataSetChanged();
                        refresh();
                    }
                }).show();
    }

    private static String join(List<String> l) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < l.size(); i++) s.append(i > 0 ? ", " : "").append(l.get(i));
        return s.toString();
    }

    private void addItem(Db.Item it, EditText count, Runnable refill) {
        if (ed == null) { toast("Open a save first."); return; }
        long n;
        try { n = Long.parseLong(count.getText().toString().trim()); } catch (NumberFormatException e) { n = 1; }
        if (n < 1) n = 1;
        long now = ed.grant(it.kind, it.id, n);
        toast("Added " + n + " x " + it.name + " (you now have " + now + ")");
        if (refill != null) refill.run();
        updateStatus();
    }

    private View buildItems() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText q = new EditText(this);
        q.setHint("Search by item or unit name");
        q.setSingleLine(true);
        box.addView(q);

        LinearLayout rc = row();
        rc.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        TextView cl = text("Add count:");
        rc.addView(cl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final EditText count = new EditText(this);
        count.setInputType(InputType.TYPE_CLASS_NUMBER);
        count.setText("1");
        count.setSingleLine(true);
        count.setGravity(Gravity.CENTER);
        count.setPadding(dp(4), count.getPaddingTop(), dp(4), count.getPaddingBottom());
        rc.addView(count, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout flt = new LinearLayout(this);
        flt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r1 = row();
        List<String> slots = new ArrayList<String>();
        for (String x : SLOTS) slots.add(x);
        final Spinner slot = spinner(slots);
        addW(r1, slot);
        final List<String> typeList = new ArrayList<String>();
        typeList.add("All types");
        final ArrayAdapter<String> typeAd = Ui.adapter(this, android.R.layout.simple_spinner_item, typeList);
        typeAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        final Spinner type = new Spinner(this);
        type.setAdapter(typeAd);
        addW(r1, type);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        r1.addView(helpIcon("Left: slot (Weapon, Armor, Materia...). Right: item type inside that slot (Sword, Katana, Hat...). Pick a slot first; the type list fills from it."), helpLp());
        flt.addView(r1);
        final CheckBox tmrOnly = new CheckBox(this);
        tmrOnly.setText("TMR / STMR only");
        tmrOnly.setChecked(false);
        flt.addView(tmrOnly);
        final EditText[] countRef = {count};
        final Runnable[] itemsFillRef = new Runnable[1];

        final Runnable[] fillRef = new Runnable[1];
        final BannerFilter bf = new BannerFilter("Banner range (the TMR's unit's first banner)", "Hide owned items (already in this save)", new Runnable() {
            public void run() { if (fillRef[0] != null) fillRef[0].run(); }
        });
        flt.addView(bf.view);
        rc.addView(helpIcon("Add TMR / materia / equipment. Set the count, then tap Add on the item's row."), helpLp());
        rc.addView(spacer(), new LinearLayout.LayoutParams(0, 1, 1));
        rc.addView(filterButton(new View.OnClickListener() {
            public void onClick(View x) { popup("Filter", flt); }
        }), miniLp());
        box.addView(rc);
        final TextView found = small("");
        box.addView(found);

        final ListView lv = new ListView(this);
        lv.setItemsCanFocus(true);
        final List<Db.Item> shownItems = new ArrayList<Db.Item>();
        final List<String> haveText = new ArrayList<String>();
        final List<String> rowText = new ArrayList<String>();
        final BaseAdapter ad = new BaseAdapter() {
            public int getCount() { return shownItems.size(); }
            public Object getItem(int pos) { return shownItems.get(pos); }
            public long getItemId(int pos) { return pos; }
            public View getView(int pos, View old, ViewGroup parent) {
                LinearLayout row = (LinearLayout) old;
                if (row == null) {
                    row = row();
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(4), 0, dp(4));
                    ImageView ic = new ImageView(MainActivity.this);
                    ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    ic.setPadding(0, 0, dp(8), 0);
                    row.addView(ic, new LinearLayout.LayoutParams(dp(48), dp(40)));
                    TextView info = text("");
                    row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    Button add = button("Add", new View.OnClickListener() {
                        public void onClick(View x) { addItem((Db.Item) x.getTag(), countRef[0], itemsFillRef[0]); }
                    });
                    add.setMinWidth(dp(72));
                    LinearLayout col = new LinearLayout(MainActivity.this);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setGravity(Gravity.CENTER_HORIZONTAL);
                    TextView have = small("");
                    have.setGravity(Gravity.CENTER);
                    col.addView(have, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    col.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    Button rem = button("Remove", new View.OnClickListener() {
                        public void onClick(View x) { removeItem((Db.Item) x.getTag(), countRef[0], itemsFillRef[0]); }
                    });
                    rem.setMinWidth(dp(72));
                    col.addView(rem, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    Button unc = button("Unclaim", new View.OnClickListener() {
                        public void onClick(View x) { unclaimTmr((Db.Item) x.getTag(), itemsFillRef[0]); }
                    });
                    unc.setMinWidth(dp(72));
                    col.addView(unc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    row.addView(col, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    Ui.apply(row);
                    rem.setBackground(Ui.redBg(MainActivity.this));
                    unc.setBackground(Ui.redBg(MainActivity.this));
                }
                ((TextView) row.getChildAt(1)).setText(richItem(rowText.get(pos)));
                LinearLayout col = (LinearLayout) row.getChildAt(2);
                ((TextView) col.getChildAt(0)).setText(haveText.get(pos));
                col.getChildAt(1).setTag(shownItems.get(pos));
                col.getChildAt(2).setTag(shownItems.get(pos));
                Db.Item cur = shownItems.get(pos);
                col.getChildAt(3).setTag(cur);
                col.getChildAt(3).setVisibility(cur.tmr && db.tmrOwner.containsKey(cur.id) ? View.VISIBLE : View.GONE);
                setIcon((ImageView) row.getChildAt(0), Icons.item(shownItems.get(pos)));
                return row;
            }
        };
        lv.setAdapter(ad);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        final Runnable fill = new Runnable() {
            public void run() {
                String s = q.getText().toString().trim().toLowerCase(Locale.ROOT);
                String sl = slot.getSelectedItemPosition() <= 0 ? null : SLOTS[slot.getSelectedItemPosition()];
                Object tp = type.getSelectedItem();
                String ty = tp == null || type.getSelectedItemPosition() <= 0 ? null : tp.toString();
                shownItems.clear();
                rowText.clear();
                haveText.clear();
                int total = 0;
                for (Db.Item it : db.items) {
                    if (tmrOnly.isChecked() && !it.tmr) continue;
                    if (sl != null && !sl.equals(it.slot)) continue;
                    if (ty != null && !ty.equals(it.type)) continue;
                    if (!bf.pass(it.banner, it.est)) continue;
                    if (bf.hideOwned.isChecked() && ed != null && ed.owned(it.kind, it.id) > 0) continue;
                    if (!s.isEmpty() && !it.name.toLowerCase(Locale.ROOT).contains(s)) continue;
                    total++;
                    if (shownItems.size() >= 400) continue;
                    shownItems.add(it);
                    String kind = it.kind == 22 ? "Materia" : it.slot + (it.type.isEmpty() || it.type.equals(it.slot) ? "" : " / " + it.type);
                    String when = it.banner < 0 ? "no banner" : (it.est ? "~" : "") + db.banners.get(it.banner).label() + (it.est ? " (est.)" : "");
                    String[] gd = GearData.get(MainActivity.this, String.valueOf(it.id));
                    rowText.add(it.name + "\n" + kind + "  ·  " + when + (gd == null ? "" : "\n" + gd[0] + (gd[1].isEmpty() ? "" : "\n" + gd[1])));
                    haveText.add("Have " + (ed == null ? "-" : String.valueOf(ed.owned(it.kind, it.id))));
                }
                found.setText(total + " items" + (total > shownItems.size() ? " (showing the first " + shownItems.size() + "; search to narrow)" : ""));
                ad.notifyDataSetChanged();
            }
        };
        onPick(slot, new Runnable() {
            public void run() {
                String sl = slot.getSelectedItemPosition() <= 0 ? null : SLOTS[slot.getSelectedItemPosition()];
                java.util.TreeSet<String> types = new java.util.TreeSet<String>();
                if (sl != null && !sl.equals("Materia"))
                    for (Db.Item it : db.items) if (sl.equals(it.slot) && !it.type.isEmpty() && !it.type.equals(sl)) types.add(it.type);
                typeList.clear();
                typeList.add("All types");
                typeList.addAll(types);
                typeAd.notifyDataSetChanged();
                type.setSelection(0);
                fill.run();
            }
        });
        onPick(type, fill);
        fillRef[0] = fill;
        itemsFill = fill;
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence x, int a, int b, int c) { }
            public void onTextChanged(CharSequence x, int a, int b, int c) { }
            public void afterTextChanged(Editable x) { fill.run(); }
        });
        View.OnClickListener refill = new View.OnClickListener() {
            public void onClick(View v) { fill.run(); }
        };
        tmrOnly.setOnClickListener(refill);
        itemsFillRef[0] = fill;
        return box;
    }

    // ------------------------------------------------------------------ profile tab

    static String speedsJs(android.content.Context c) {
        StringBuilder sb = new StringBuilder("[");
        try { for (Double v : SpeedService.speeds(c)) { if (sb.length() > 1) sb.append(","); sb.append(v == Math.rint(v) ? String.valueOf(v.intValue()) : String.valueOf(v)); } } catch (Exception ignored) { }
        return sb.append("]").toString();
    }

    // ------------------------------------------------------------------ live edit (through the patched game)

    private String liveHash;

    /** Reads the save that the running game holds, no export needed. */
    private String liveSlot;   // slot id the editor holds (null = the game's active one)

    private void liveOpen() {
        toast("Looking for the game's saves...");
        new Thread(new Runnable() {
            public void run() {
                java.util.List<CloudSync.Slot> sl = null;
                try { sl = CloudSync.slots(); } catch (Exception e) { sl = null; }
                final java.util.List<CloudSync.Slot> fl = sl;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fl == null || fl.size() < 2) { liveOpen(null); return; }
                        slotPicker("Open save from game", fl, new SlotPick() {
                            public void pick(CloudSync.Slot x) { liveOpen(x.active ? null : x.id); }
                        });
                    }
                });
            }
        }).start();
    }

    private interface SlotPick { void pick(CloudSync.Slot s); }

    /** Popup of save slots: the active one on top, marked Active. */
    private void slotPicker(String title, java.util.List<CloudSync.Slot> sl, final SlotPick cb) {
        final android.app.AlertDialog[] dd = new android.app.AlertDialog[1];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(6), dp(12), dp(6));
        for (final CloudSync.Slot s : sl) {
            String when = s.time > 0 ? new java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(new java.util.Date(s.time)) : "--";
            String mb = s.bytes <= 0 ? "" : "  \u2022  " + (s.bytes >= 1048576 ? String.format(java.util.Locale.US, "%.1f MB", s.bytes / 1048576.0) : (s.bytes / 1024) + " KB");
            Button b = button((s.active ? "\u2605 Active  \u2022  " : "") + s.name + "\n" + when + mb + (s.missing ? "  \u2022  not on this phone" : ""), new View.OnClickListener() {
                public void onClick(View x) { if (dd[0] != null) dd[0].dismiss(); cb.pick(s); }
            });
            b.setAllCaps(false);
            b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            box.addView(b, lp);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        dd[0] = new Dlg(this).setTitle(title).setView(sv).setNegativeButton("Cancel", null).show();
    }

    private void liveOpen(final String slot) {
        toast("Reading the save from the game...");
        new Thread(new Runnable() {
            public void run() {
                String err = null, txt = null, h = null;
                try {
                    if (slot != null && !CloudSync.pick(slot)) throw new Exception("The game could not open that save slot.");
                    byte[] b = CloudSync.fetchLocal();
                    h = Sync.hash(b);
                    txt = new String(b, "UTF-8");
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                } finally {
                    if (slot != null) CloudSync.pick(null);
                }
                final String ee = err, tt = txt, hh = h;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ee != null) {
                            alert("Could not open from the game", ee + "\n\nThe game must be open and the script installed. Tap Install Script (Game Patch tab), then try again. No switch in the Patch List is needed for this.");
                            return;
                        }
                        try {
                            ed = new Editor(db, tt);
                            mergePresets();
                            liveHash = hh;
                            liveSlot = slot;
                            saveName = slot == null ? "Game save" : "Game save (" + slot + ")";
                            picked.clear();
                            search.setText("");
                            refresh();
                            if (unitsFill != null) unitsFill.run();
                            if (itemsFill != null) itemsFill.run();
                            if (bestFill != null) bestFill.run();
                            if (missFill != null) missFill.run();
                            if (gambit != null) gambit.refresh();
                            if (summFill != null) summFill.run();
            if (missFill != null) missFill.run();
            if (summFill != null) summFill.run();
                            toast("Loaded " + ed.roster.size() + " units from the game.");
                        } catch (Exception e) {
                            alert("Could not open the save", String.valueOf(e.getMessage()));
                        }
                    }
                });
            }
        }).start();
    }

    /** Writes the edited save into the game and restarts it. */
    private void liveApply() {
        if (ed == null || liveHash == null) { alert("Nothing to apply", "Tap Open from game first, edit, then tap Apply to game."); return; }
        List<String> bad = ed.check();
        if (!bad.isEmpty()) {
            StringBuilder b = new StringBuilder("Nothing was sent to the game because:\n");
            for (int i = 0; i < Math.min(10, bad.size()); i++) b.append("- ").append(bad.get(i)).append('\n');
            alert("Check failed", b.toString());
            return;
        }
        final byte[] data;
        try { data = ed.text().getBytes("UTF-8"); } catch (Exception e) { alert("Could not apply", String.valueOf(e.getMessage())); return; }
        new Thread(new Runnable() {
            public void run() {
                if (liveSlot != null) CloudSync.pick(liveSlot);
                final String now = CloudSync.hashNow();
                if (liveSlot != null) CloudSync.pick(null);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (now == null) { alert("The game does not answer", "Open the game (script installed) and try again."); return; }
                        if (!now.equals(liveHash)) {
                            new Dlg(MainActivity.this).setTitle("The game save changed")
                                    .setMessage("The game saved again after you opened it. Applying now replaces that newer progress with your edit. "
                                            + "The game's current save is kept as a backup.")
                                    .setPositiveButton("Apply anyway", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) { liveSend(data); }
                                    })
                                    .setNegativeButton("Cancel", null).show();
                        } else {
                            new Dlg(MainActivity.this).setTitle("Apply to the game?")
                                    .setMessage("Your edit goes into the game and the game restarts. The old save is kept as a backup.")
                                    .setPositiveButton("Apply", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) { liveSend(data); }
                                    })
                                    .setNegativeButton("Cancel", null).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void liveSend(final byte[] data) {
        toast("Sending to the game...");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    if (liveSlot != null) CloudSync.pick(liveSlot);
                    byte[] cur = CloudSync.fetchLocal();          // keep the game's current save in the app too
                    File dir = new File(getFilesDir(), "live_backups");
                    dir.mkdirs();
                    OutputStream o = new java.io.FileOutputStream(new File(dir, "game_save_" + System.currentTimeMillis() + ".json"));
                    o.write(cur);
                    o.close();
                    File[] fs = dir.listFiles();
                    if (fs != null && fs.length > 5) {
                        java.util.Arrays.sort(fs);
                        for (int i = 0; i < fs.length - 5; i++) fs[i].delete();
                    }
                    err = CloudSync.sendToGame(data);
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                } finally {
                    if (liveSlot != null && err != null) CloudSync.pick(null);
                }
                final String ee = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ee != null) { alert("Could not apply", ee); return; }
                        liveHash = null;
                        if (ed != null) { ed.dirty = false; updateStatus(); }
                        toast("Applied. The game restarts.");
                        new android.os.Handler().postDelayed(new Runnable() {
                            public void run() {
                                try {
                                    Intent li = getPackageManager().getLaunchIntentForPackage("com.exvius.offline.forever");
                                    if (li != null) startActivity(li);
                                } catch (Exception ignored) { }
                            }
                        }, 2200);
                    }
                });
            }
        }).start();
    }

    /** Lists the game's own folder and writes a short digest (name, size, first lines) of every table-like file. Small enough to send. */
    private long logAt;

    /** Asks the game for text (patch log, name list), keeps the last ~200 KB and writes it into the sync folder. */
    /** Export file name with the app version before the extension, e.g. ffbe_log_v0.19.12.txt. */
    private String vname(String name) {
        String v = "";
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        if (v == null || v.isEmpty()) return name;
        if (name.startsWith("ffbe_log")) v = v + "_" + Sync.deviceTag();   // phone and tablet must not overwrite each other's log
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name + "_v" + v : name.substring(0, dot) + "_v" + v + name.substring(dot);
    }

    /** Item row text: bigger name, then kind and banner, then the description (small), then the stats lines in gold. */
    private CharSequence richItem(String t) {
        String[] ln = t.split("\n");
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (int i = 0; i < ln.length; i++) {
            int a = sb.length();
            sb.append(ln[i]);
            int b = sb.length();
            if (i == 0) {
                sb.setSpan(new android.text.style.RelativeSizeSpan(1.2f), a, b, 0);
                sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), a, b, 0);
            } else {
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.82f), a, b, 0);
                sb.setSpan(new android.text.style.ForegroundColorSpan(i >= 3 ? Ui.GOLD : Ui.DIM), a, b, 0);
            }
            if (i < ln.length - 1) sb.append('\n');
        }
        return sb;
    }

    private String exportText(String cmd, int wait, String name0) throws Exception {
        String name = vname(name0);
        if (Sync.tree(this) == null) throw new Exception("Choose the sync folder first (Cloud sync section).");
        String r = cmd.equals("qlog") ? Speed.qlog(wait) : Speed.sendAll(cmd, wait);
        byte[] b = r.getBytes("UTF-8");
        if (b.length > 600000) {
            int from = b.length - 600000;
            while (from < b.length && b[from] != 10) from++;
            b = java.util.Arrays.copyOfRange(b, Math.min(b.length, from + 1), b.length);
        }
        Sync.putFile(this, name, "text/plain", b);
        int gone = name.startsWith("ffbe_log") ? Sync.cleanLogs(this) : 0;   // keep only the newest logs in the folder
        return name + " written to " + Sync.folderName(this) + " (" + b.length + " bytes)." + (gone > 0 ? " Removed " + gone + " old log file" + (gone == 1 ? "" : "s") + "." : "");
    }

    /** Quietly copies the in-game patch log to ffbe_log.txt whenever the app comes to the front and the game is running. */
    private void autoLog() {
        if (System.currentTimeMillis() - logAt < 20000) return;
        logAt = System.currentTimeMillis();
        new Thread(new Runnable() {
            public void run() {
                try { if (Sync.useFolder(MainActivity.this)) exportText("qlog", 3000, "ffbe_log.txt"); } catch (Exception ignored) { }
            }
        }).start();
    }

    /** Copies every game table (mst_decoded and text_decoded .ndjson) into zip parts of about 6 MB in the sync folder. */
    private void dumpAllTables(final TextView res) {
        res.setText("Asking the game for its file list. The game must be open...");
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    if (Sync.tree(MainActivity.this) == null) throw new Exception("Choose the sync folder first (Cloud sync section).");
                    String r = Speed.sendBig("flist", 60000);
                    if (!r.startsWith("D ")) throw new Exception("The game did not answer. Tap Install Script first, then open the game.");
                    String[] lines = new String(android.util.Base64.decode(r.substring(2), android.util.Base64.DEFAULT), "UTF-8").split("\n");
                    final List<String> paths = new ArrayList<String>();
                    final List<Long> sizes = new ArrayList<Long>();
                    long total = 0;
                    for (String ln : lines) {
                        int tab = ln.lastIndexOf('\t');
                        if (tab < 0) continue;
                        String path = ln.substring(0, tab);
                        long size;
                        try { size = Long.parseLong(ln.substring(tab + 1).trim()); } catch (Exception e) { continue; }
                        if (size <= 0 || !path.endsWith(".ndjson")) continue;
                        if (path.indexOf("/static/mst_decoded/") < 0 && path.indexOf("/static/text_decoded/") < 0) continue;
                        paths.add(path); sizes.add(size); total += size;
                    }
                    if (paths.isEmpty()) throw new Exception("No table files found in the game.");
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(8 << 20);
                    java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(bo);
                    int part = 1, inPart = 0;
                    StringBuilder done = new StringBuilder();
                    long doneBytes = 0;
                    for (int f = 0; f < paths.size(); f++) {
                        final String path = paths.get(f);
                        final long size = sizes.get(f);
                        final String shown = "Table " + (f + 1) + " of " + paths.size() + ": " + path.substring(path.lastIndexOf('/') + 1) + "  (" + (doneBytes >> 20) + " of " + (total >> 20) + " MB read)";
                        ui.post(new Runnable() { public void run() { res.setText(shown); } });
                        zo.putNextEntry(new java.util.zip.ZipEntry(path.substring(path.indexOf("/static/") + 8)));
                        long off = 0;
                        while (off < size) {
                            int want = (int) Math.min(786432, size - off);
                            String d = Speed.sendBig("fget " + off + " " + want + " " + path, 60000);
                            if (!d.startsWith("D ")) throw new Exception("Lost the game while reading " + path);
                            byte[] b = android.util.Base64.decode(d.substring(2), android.util.Base64.DEFAULT);
                            if (b.length == 0) break;
                            zo.write(b);
                            off += b.length;
                        }
                        zo.closeEntry();
                        doneBytes += size;
                        inPart++;
                        done.append(path.substring(path.lastIndexOf('/') + 1)).append(' ').append(size).append('\n');
                        if (bo.size() > 6 * 1048576) {
                            zo.close();
                            Sync.putFile(MainActivity.this, vname("ffbe_alltables_part" + part + ".zip"), "application/zip", bo.toByteArray());
                            part++; inPart = 0;
                            bo = new java.io.ByteArrayOutputStream(8 << 20);
                            zo = new java.util.zip.ZipOutputStream(bo);
                        }
                    }
                    zo.close();
                    if (inPart > 0) Sync.putFile(MainActivity.this, vname("ffbe_alltables_part" + part + ".zip"), "application/zip", bo.toByteArray());
                    else part--;
                    msg = "Done. " + paths.size() + " tables (" + (total >> 20) + " MB raw) written as " + part + " zip file(s) named ffbe_alltables_part#_v" + "... in the sync folder. Your cloud app uploads them.";
                } catch (Exception e) {
                    msg = "Export failed: " + e.getMessage();
                }
                final String t = msg;
                ui.post(new Runnable() { public void run() { res.setText(t); } });
            }
        }).start();
    }

    private void exportTables(final TextView res) {
        res.setText("Asking the game for its file list. The game must be open with the script installed...");
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    String r = Speed.sendBig("flist", 60000);
                    if (!r.startsWith("D ")) throw new Exception("The game did not answer. Tap Install Script first, then open the game.");
                    byte[] listing = android.util.Base64.decode(r.substring(2), android.util.Base64.DEFAULT);
                    String[] lines = new String(listing, "UTF-8").split("\n");
                    java.util.regex.Pattern want = java.util.regex.Pattern.compile("(?i)(MISSION|SWITCH|BEAST|CHALLENGE|ESPER|SUMMON)");
                    java.util.regex.Pattern skip = java.util.regex.Pattern.compile("(?i)\\.(png|jpg|jpeg|ogg|mp3|wav|m4a|mp4|ttf|otf|ccz|plist|apk|so|dex|zip)$");
                    StringBuilder dg = new StringBuilder();
                    int files = 0;
                    for (final String ln : lines) {
                        int tab = ln.lastIndexOf('\t');
                        if (tab < 0) continue;
                        String path = ln.substring(0, tab);
                        long size;
                        try { size = Long.parseLong(ln.substring(tab + 1).trim()); } catch (Exception e) { continue; }
                        String nm = path.substring(path.lastIndexOf('/') + 1);
                        if (size <= 0 || !want.matcher(nm).find() || skip.matcher(nm).find() || files >= 120) continue;
                        files++;
                        dg.append("=== ").append(path).append("  ").append(size).append(" bytes\n");
                        try {
                            String d = Speed.sendBig("fget 0 1600 " + path, 30000);
                            byte[] b = d.startsWith("D ") && d.length() > 2 ? android.util.Base64.decode(d.substring(2), android.util.Base64.DEFAULT) : new byte[0];
                            int printable = 0;
                            for (byte x : b) if ((x >= 32 && x < 127) || x == 10 || x == 9 || x < 0) printable++;
                            if (b.length > 0 && printable * 10 >= b.length * 9) {
                                String[] ls = new String(b, "UTF-8").split("\n");
                                for (int i = 0; i < Math.min(4, ls.length); i++) dg.append(ls[i].length() > 600 ? ls[i].substring(0, 600) : ls[i]).append('\n');
                            } else {
                                StringBuilder h = new StringBuilder("(binary) ");
                                for (int i = 0; i < Math.min(48, b.length); i++) h.append(String.format("%02x", b[i] & 255));
                                dg.append(h).append('\n');
                            }
                        } catch (Exception e) { dg.append("(could not read: ").append(e.getMessage()).append(")\n"); }
                    }
                    byte[] dgb = dg.toString().getBytes("UTF-8");
                    msg = "Listed " + lines.length + " files, described " + files + " table-like files. ";
                    if (Sync.tree(MainActivity.this) != null) {
                        String a = Sync.putFile(MainActivity.this, vname("ffbe_listing.txt"), "text/plain", listing);
                        String b2 = Sync.putFile(MainActivity.this, vname("ffbe_digest.txt"), "text/plain", dgb);
                        msg += "Wrote " + a + " and " + b2 + " in the sync folder. Your cloud app uploads them.";
                    } else {
                        OutputStream po = new FileOutputStream(new File(getCacheDir(), "pending_tables.zip"));
                        po.write(dgb);
                        po.close();
                        runOnUiThread(new Runnable() {
                            public void run() {
                                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                                i.addCategory(Intent.CATEGORY_OPENABLE);
                                i.setType("text/plain");
                                i.putExtra(Intent.EXTRA_TITLE, vname("ffbe_digest.txt"));
                                startActivityForResult(i, WRITE_TABLES);
                            }
                        });
                    }
                } catch (Exception e) {
                    msg = "Export failed: " + e.getMessage();
                }
                final String t = msg;
                ui.post(new Runnable() { public void run() { res.setText(t); } });
            }
        }).start();
    }

    private void writeTables(Uri u) {
        try {
            File f = new File(getCacheDir(), "pending_tables.zip");
            OutputStream o = getContentResolver().openOutputStream(u, "wt");
            o.write(readAll(new FileInputStream(f)));
            o.close();
            f.delete();
            alert("Saved", "Wrote " + displayName(u) + ". Send this zip back.");
        } catch (Exception e) {
            alert("Could not write the file", String.valueOf(e.getMessage()));
        }
    }

    private void saveCopy() {
        if (ed == null) { toast("Open a save first."); return; }
        List<String> bad = ed.check();
        if (!bad.isEmpty()) {
            StringBuilder b = new StringBuilder("The file was not written because:\n");
            for (int i = 0; i < Math.min(10, bad.size()); i++) b.append("- ").append(bad.get(i)).append('\n');
            alert("Check failed", b.toString());
            return;
        }
        try {   // the file picker can outlive this screen (Android may restart the app), so keep the text on disk
            OutputStream po = new java.io.FileOutputStream(new File(getCacheDir(), "pending_save.json"));
            po.write(ed.text().getBytes("UTF-8"));
            po.close();
        } catch (Exception e) {
            alert("Could not prepare the file", String.valueOf(e.getMessage()));
            return;
        }
        String base = saveName.toLowerCase(Locale.ROOT).endsWith(".json") ? saveName.substring(0, saveName.length() - 5) : saveName;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, base + "_edited.json");
        startActivityForResult(i, WRITE_SAVE);
    }

    private void writeSave(Uri u) {
        try {
            OutputStream o = getContentResolver().openOutputStream(u, "wt");
            File pend = new File(getCacheDir(), "pending_save.json");
            byte[] data = pend.exists() ? readAll(new java.io.FileInputStream(pend)) : (ed != null ? ed.text().getBytes("UTF-8") : null);
            if (data == null) { o.close(); alert("Could not write the file", "Nothing to write. Open the save and tap Save edited copy again."); return; }
            o.write(data);
            o.close();
            pend.delete();
            if (ed != null) { ed.dirty = false; updateStatus(); }
            alert("Saved", "Wrote " + displayName(u) + ".\n\nYour original file is unchanged. Import the new file from the game's title screen.");
        } catch (Exception e) {
            alert("Could not write the file", String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ speed tab

    /** A collapsed Patch List section with an On/Off switch (default Off) saved under pref key. */
    /** A greyed-out header that cannot be opened. */
    private Button disabledSection(String title) {
        Button b = button("\u25b8 " + title, null);
        b.setEnabled(false);
        b.setAlpha(0.45f);
        return b;
    }

    /** Every collapsible section (Patch List, Cloud sync, Speed button, Developer Only, and any new one) starts from this box:
     *  gold border like the character rows in Gambit, shown both collapsed and open. Build new collapsibles with goldBox(). */
    private LinearLayout goldBox() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(2), dp(2), dp(2), dp(2));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setStroke(Math.max(2, dp(2)), Ui.GOLD);
        g.setCornerRadius(dp(6));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    /** One shared helper: a feature that needs "Display over other apps" was just turned on. If the permission is missing,
     *  explain why and offer to open the system page. "No" carries on without it. */
    private void askOverlay(String feature, String why) {
        if (Settings.canDrawOverlays(this)) return;
        new Dlg(this).setTitle("Allow Display over other apps?")
                .setMessage(feature + " " + why + "\n\nAndroid needs the permission \"Display over other apps\" for FFBE Kupo Forge. Open that page now?")
                .setPositiveButton("Yes, open it", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
                        catch (Exception e) { toast("Could not open the page. Find it in Android settings under Apps > Special access."); }
                    }
                })
                .setNegativeButton("No, carry on without it", null).show();
    }

    /** The reason text for the Patch List keys that need the permission, or null. */
    private static String overlayWhy(String key) {
        if ("patchCloud".equals(key)) return "asks you in a box over the game whether to load a newer cloud save, and that box needs the permission.";
        if ("patchLive".equals(key)) return "opens this app over the game when you tap Open Kupo Forge in the game, and Android only allows that with the permission.";
        return null;
    }

    /** Colours a Patch List header by how many of its switches are on (slate none, amber some, green all) and shows the count. Returns the repaint. */
    private Runnable headState(final Button head, final String title, final String[] keys, final boolean[] open) {
        return new Runnable() {
            public void run() {
                android.content.SharedPreferences sp = getSharedPreferences("editor", MODE_PRIVATE);
                int n = 0;
                for (String k : keys) if (sp.getBoolean(k, false)) n++;
                int st = n == 0 ? 0 : n == keys.length ? 2 : 1;
                head.setBackground(Ui.stateBg(MainActivity.this, st));
                head.setTextColor(st == 0 ? Ui.CREAM : Color.WHITE);
                head.setText((open[0] ? "\u25be " : "\u25b8 ") + title + "   " + n + "/" + keys.length + (open[0] ? "  (hide)" : "  (show)"));
            }
        };
    }

    private LinearLayout patchSection(final String title, final String key, String switchText, String info, View extra) {
        final LinearLayout box = goldBox();
        box.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        final android.widget.Switch sw = new android.widget.Switch(this);
        sw.setText(switchText + "  ");
        sw.setTextColor(Ui.CREAM);
        sw.setChecked(getSharedPreferences("editor", MODE_PRIVATE).getBoolean(key, false));
        sw.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                getSharedPreferences("editor", MODE_PRIVATE).edit().putBoolean(key, sw.isChecked()).apply();
                if (sw.isChecked() && overlayWhy(key) != null) askOverlay(title, overlayWhy(key));
            }
        });
        body.addView(withHelp(sw, info));
        if (extra != null) body.addView(extra);
        final Button head = button("\u25b8 " + title + " (show)", null);
        final boolean[] op = new boolean[1];
        final Runnable paint = headState(head, title, new String[]{key}, op);
        sw.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                getSharedPreferences("editor", MODE_PRIVATE).edit().putBoolean(key, sw.isChecked()).apply();
                if (sw.isChecked() && overlayWhy(key) != null) askOverlay(title, overlayWhy(key));
                paint.run();
            }
        });
        head.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                op[0] = body.getVisibility() != View.VISIBLE;
                body.setVisibility(op[0] ? View.VISIBLE : View.GONE);
                paint.run();
            }
        });
        paint.run();
        box.addView(head);
        box.addView(body);
        return box;
    }

    /** One collapsible section holding several switches; each has a gold title above its white text. */
    private LinearLayout patchGroup(final String title, String[][] items0) {
        java.util.ArrayList<String[]> keep = new java.util.ArrayList<String[]>();
        for (String[] it : items0) if (Flags.LOGS || !(it[0].equals("patchOvNote") || it[0].equals("patchDlog"))) keep.add(it);
        String[][] items = keep.toArray(new String[0][]);
        final LinearLayout box = goldBox();
        box.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        final java.util.ArrayList<android.widget.Switch> sws = new java.util.ArrayList<android.widget.Switch>();
        final java.util.ArrayList<View.OnClickListener> clicks = new java.util.ArrayList<View.OnClickListener>();
        for (final String[] it : items) {
            TextView t = text(it[1]);
            t.setTextColor(Ui.GOLD);
            t.setTextSize(16);
            t.setTypeface(Typeface.SERIF, Typeface.BOLD);
            t.setPadding(0, dp(10), 0, dp(2));
            body.addView(t);
            final android.widget.Switch sw = new android.widget.Switch(this);
            sw.setText(it[2] + "  ");
            sw.setTextColor(Ui.CREAM);
            sw.setChecked(getSharedPreferences("editor", MODE_PRIVATE).getBoolean(it[0], false));
            View.OnClickListener c0 = new View.OnClickListener() {
                public void onClick(View x) {
                    getSharedPreferences("editor", MODE_PRIVATE).edit().putBoolean(it[0], sw.isChecked()).apply();
                    if (sw.isChecked() && overlayWhy(it[0]) != null) askOverlay(it[1], overlayWhy(it[0]));
                }
            };
            sw.setOnClickListener(c0);
            sws.add(sw);
            clicks.add(c0);
            body.addView(withHelp(sw, it[3]));
            if (it[4] != null) {
                TextView w = text(it[4]);
                w.setTextColor(0xFFFF4040);
                w.setTextSize(13);
                body.addView(w);
            }
        }
        final Button head = button("\u25b8 " + title + " (show)", null);
        final boolean[] op = new boolean[1];
        String[] gk = new String[items.length];
        for (int q = 0; q < items.length; q++) gk[q] = items[q][0];
        final Runnable paint = headState(head, title, gk, op);
        for (int q = 0; q < sws.size(); q++) {
            final android.widget.Switch sq = sws.get(q);
            final View.OnClickListener old0 = clicks.get(q);
            sq.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) { old0.onClick(x); paint.run(); }
            });
        }
        head.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                op[0] = body.getVisibility() != View.VISIBLE;
                body.setVisibility(op[0] ? View.VISIBLE : View.GONE);
                paint.run();
            }
        });
        paint.run();
        box.addView(head);
        box.addView(body);
        return box;
    }

    // ------------------------------------------------------------------ cloud sync

    private TextView syncStatus;

    private void syncRefresh() {
        if (syncStatus == null) return;
        final Uri t = Sync.tree(this);
        if (t == null) { syncStatus.setText("No sync folder chosen yet."); return; }
        new Thread(new Runnable() {
            public void run() {
                String r;
                try {
                    Sync.Item n = Sync.newest(MainActivity.this, t);
                    r = "Folder: " + Uri.decode(String.valueOf(t.getLastPathSegment())) + "\nNewest save there: "
                            + (n == null ? "none yet" : n.name + " (" + Sync.when(n.time) + ")")
                            + "\nLast auto upload or load on this phone: " + Sync.when(Sync.last(MainActivity.this))
                            + "\nLast cloud sync check: " + getSharedPreferences("editor", MODE_PRIVATE).getString("syncNote", "none yet (needs the game open and Auto cloud sync on)");
                } catch (Exception e) {
                    r = "Could not read the sync folder: " + e.getMessage() + "\nChoose it again.";
                }
                final String rr = r;
                runOnUiThread(new Runnable() { public void run() { syncStatus.setText(rr); } });
            }
        }).start();
    }

    private void syncPush() {
        if (ed == null) { alert("Nothing to push", "Open a save first (Save Editor tab), then push it."); return; }
        final Uri t = Sync.tree(this);
        if (t == null) { alert("No sync folder", "Choose the sync folder first."); return; }
        final byte[] data;
        try { data = ed.text().getBytes("UTF-8"); } catch (Exception e) { alert("Could not push", String.valueOf(e.getMessage())); return; }
        new Thread(new Runnable() {
            public void run() {
                java.util.List<CloudSync.Slot> sl = null;
                try { sl = CloudSync.slots(); } catch (Exception e) { sl = null; }
                final java.util.List<CloudSync.Slot> fl = sl;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fl == null || fl.size() == 0) { pushTo(t, data, null); return; }
                        if (fl.size() == 1) { pushTo(t, data, fl.get(0).id); return; }
                        slotPicker("Push to which save's cloud file?", fl, new SlotPick() {
                            public void pick(CloudSync.Slot x) { pushTo(t, data, x.id); }
                        });
                    }
                });
            }
        }).start();
    }

    private void pushTo(final Uri t, final byte[] data, final String slot) {
        new Thread(new Runnable() {
            public void run() {
                Sync.Item n = null;
                String err = null;
                try { n = Sync.find(MainActivity.this, t, slot); } catch (Exception e) { err = String.valueOf(e.getMessage()); }
                final Sync.Item nn = n;
                final String ee = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ee != null) { alert("Could not read the sync folder", ee); return; }
                        if (nn != null) {
                            new Dlg(MainActivity.this)
                                    .setTitle("Replace the cloud save?")
                                    .setMessage("The sync folder has " + nn.name + " from " + Sync.when(nn.time)
                                            + ". Pushing replaces it with the open save. The old copy is kept in the backups folder. Nothing is merged.")
                                    .setPositiveButton("Push", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) { doPush(data, slot); }
                                    })
                                    .setNegativeButton("Cancel", null).show();
                        } else doPush(data, slot);
                    }
                });
            }
        }).start();
    }

    private void doPush(final byte[] data, final String slot) {
        new Thread(new Runnable() {
            public void run() {
                String r, title;
                try {
                    String note = Sync.push(MainActivity.this, data, true, slot);
                    title = "Pushed";
                    r = "Wrote " + Sync.fileFor(slot) + " to the sync folder. " + note + "Your cloud app uploads it.";
                } catch (Exception e) {
                    title = "Could not push";
                    r = String.valueOf(e.getMessage());
                }
                final String tt = title, rr = r;
                runOnUiThread(new Runnable() { public void run() { alert(tt, rr); syncRefresh(); } });
            }
        }).start();
    }

    private void syncPull() {
        final Uri t = Sync.tree(this);
        if (t == null) { alert("No sync folder", "Choose the sync folder first."); return; }
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                final java.util.List<CloudSync.Slot> cl = new java.util.ArrayList<CloudSync.Slot>();
                Sync.Item newest = null;
                boolean known = false;
                try {
                    java.util.List<CloudSync.Slot> gs = null;
                    try { gs = CloudSync.slots(); } catch (Exception e) { gs = null; }
                    known = gs != null && gs.size() > 0;
                    for (Sync.Item it : Sync.saves(MainActivity.this, t)) {
                        if (newest == null || it.time > newest.time) newest = it;
                        String id = Sync.slotOf(it.name);
                        if (id == null) continue;
                        CloudSync.Slot s = new CloudSync.Slot();
                        s.id = id; s.time = it.time; s.tag = it;
                        s.name = id.equals("campaign") ? "Original save" : CloudSync.shortName(id);
                        s.missing = known;
                        if (gs != null) for (CloudSync.Slot g : gs) if (g.id.equals(id)) { s.name = g.name; s.active = g.active; s.missing = false; s.bytes = g.bytes; }
                        cl.add(s);
                    }
                } catch (Exception e) { err = String.valueOf(e.getMessage()); }
                final String ee = err;
                final Sync.Item nn = newest;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ee != null) { alert("Could not read the sync folder", ee); return; }
                        if (nn == null) { alert("Nothing to pull", "The sync folder has no save yet."); return; }
                        if (cl.size() < 2) { pullFile(cl.size() == 1 ? (Sync.Item) cl.get(0).tag : nn); return; }
                        java.util.Collections.sort(cl, new java.util.Comparator<CloudSync.Slot>() {
                            public int compare(CloudSync.Slot a, CloudSync.Slot b) {
                                if (a.active != b.active) return a.active ? -1 : 1;
                                return a.time == b.time ? 0 : (a.time > b.time ? -1 : 1);
                            }
                        });
                        slotPicker("Pull which cloud save?", cl, new SlotPick() {
                            public void pick(final CloudSync.Slot x) {
                                if (!x.missing) { pullFile((Sync.Item) x.tag); return; }
                                new Dlg(MainActivity.this).setTitle("Not on this phone")
                                        .setMessage(x.name + " is in the cloud but the game on this phone has no such save slot. Load it into the editor only, "
                                                + "or create it as a new save slot in the game? Creating adds a new slot. No other save is changed or replaced, and the game is not restarted.")
                                        .setPositiveButton("Load into editor", new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface d, int w) { pullFile((Sync.Item) x.tag); }
                                        })
                                        .setNeutralButton("Create slot", new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface d, int w) { createSlotFrom(x); }
                                        })
                                        .setNegativeButton("Cancel", null).show();
                            }
                        });
                    }
                });
            }
        }).start();
    }

    private void createSlotFrom(final CloudSync.Slot x) {
        toast("Creating the save slot...");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    byte[] cb = Sync.read(MainActivity.this, ((Sync.Item) x.tag).uri);
                    if (!Sync.sane(cb)) throw new Exception("The cloud file looks unfinished. Try again in a minute.");
                    new Editor(db, new String(cb, "UTF-8"));      // must read as a save, or nothing is created
                    if (x.id.equals("campaign")) throw new Exception("The original save already exists on this phone.");
                    err = CloudSync.createSlot(cb, x.id);
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                }
                final String ee = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ee != null) alert("Could not create the slot", ee);
                        else alert("Slot created", x.name + " is now a save slot on this phone. Pick it in the game's save slot list. It was not loaded or switched to.");
                    }
                });
            }
        }).start();
    }

    private void pullFile(final Sync.Item nn) {
        if (ed != null && ed.dirty) {
            new Dlg(MainActivity.this)
                    .setTitle("Unsaved edits")
                    .setMessage("You have edits in the open save that are not saved. Pulling " + nn.name + " (" + Sync.when(nn.time)
                            + ") replaces them. Nothing is merged.")
                    .setPositiveButton("Pull and replace", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { loadSave(nn.uri);  syncRefresh(); }
                    })
                    .setNegativeButton("Cancel", null).show();
        } else {
            loadSave(nn.uri);
            syncRefresh();
        }
    }

    private LinearLayout buildSync() {
        final LinearLayout box = goldBox();
        box.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        syncStatus = text("");
        body.addView(withHelp(syncStatus, "Pick a folder that your cloud app already syncs. Once you tap Choose sync folder, select the 3 lines on the upper left corner, then select an online folder on the left bar (Google Drive, Dropbox, Syncthing, FolderSync folders). "
                + "Push puts the open save there as " + Sync.LATEST + " (other save slots: ffbe_save_<slot>.json) and keeps the old copy in a backups folder first. With several game save slots you pick which one. "
                + "Pull opens a save from that folder in the editor, and can create a missing save slot on this phone. "
                + "Use the same folder on every device, and make sure your cloud app syncs it on all of them."));
        body.addView(button("Choose sync folder", new View.OnClickListener() {
            public void onClick(View x) {
                toast("Pick the folder your cloud app syncs, then tap Use this folder.");
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), SYNC_TREE);
            }
        }));
        body.addView(button("Push open save to sync folder", new View.OnClickListener() {
            public void onClick(View x) { syncPush(); }
        }));
        body.addView(button("Pull newest save from sync folder", new View.OnClickListener() {
            public void onClick(View x) { syncPull(); }
        }));
        if (Flags.EXP) {
            TextView at = text("Auto cloud sync");
            at.setTextColor(Ui.GOLD);
            at.setTextSize(16);
            at.setTypeface(Typeface.SERIF, Typeface.BOLD);
            at.setPadding(0, dp(10), 0, dp(2));
            body.addView(at);
            final android.widget.Switch sw = new android.widget.Switch(this);
            sw.setText("On: sync the game save with the folder automatically  ");
            sw.setTextColor(Ui.CREAM);
            sw.setChecked(getSharedPreferences("editor", MODE_PRIVATE).getBoolean("patchCloud", false));
            sw.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    getSharedPreferences("editor", MODE_PRIVATE).edit().putBoolean("patchCloud", sw.isChecked()).apply();
                    if (sw.isChecked()) askOverlay("Auto cloud sync", overlayWhy("patchCloud"));
                }
            });
            body.addView(withHelp(sw, "While the game is open, this app checks the folder above about every 30 seconds. "
                    + "If you play, the new save is uploaded as " + Sync.LATEST + ". If another device uploaded a newer one, "
                    + "a Yes or No box asks if you want it. Yes backs up your phone save, loads the cloud save and restarts the game. "
                    + "No keeps your phone save and does not ask again for that cloud file. If both sides changed, you choose which one to keep. "
                    + "Nothing is merged. Needs the script installed with this switch on. "
                    + "Tip: tap the floating circle to turn it On or Off while playing."));
        }
        final Button head = button("\u25b8 Cloud sync (show)", null);
        head.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                boolean open = body.getVisibility() != View.VISIBLE;
                body.setVisibility(open ? View.VISIBLE : View.GONE);
                head.setText((open ? "\u25be " : "\u25b8 ") + "Cloud sync" + (open ? " (hide)" : " (show)"));
                if (open) syncRefresh();
            }
        });
        box.addView(head);
        box.addView(body);
        return box;
    }

    private LinearLayout buildDev() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        final TextView res = text("");
        v.addView(res);
        if (Flags.EXP) v.addView(button("Show in-game patch log", new View.OnClickListener() {
            public void onClick(View x) {
                res.setText("Asking the game for the patch log...");
                new Thread(new Runnable() {
                    public void run() {
                        String r;
                        try { r = Speed.sendAll("qtest", 5000) + "\n---\n" + Speed.qlog(5000); }
                        catch (Exception e) { r = "No answer: " + e.getMessage()
                                + "\nOpen the game first (script installed), then try again."; }
                        final String t = r;
                        ui.post(new Runnable() { public void run() { res.setText(t); } });
                    }
                }).start();
            }
        }));
        v.addView(button("Export image names (test)", new View.OnClickListener() {
            public void onClick(View x) {
                res.setText("Reading the game's package names. Takes up to two minutes...");
                new Thread(new Runnable() {
                    public void run() {
                        String r;
                        try { r = exportText("names", 150000, "ffbe_names.txt"); }
                        catch (Exception e) { r = "Could not export: " + e.getMessage() + "\nInstall the script, open the game, and choose the sync folder."; }
                        final String t = r;
                        ui.post(new Runnable() { public void run() { res.setText(t); } });
                    }
                }).start();
            }
        }));
        v.addView(button("Export ALL game tables (test)", new View.OnClickListener() {
            public void onClick(View x) { dumpAllTables(res); }
        }));
        return v;
    }

    private LinearLayout buildSpeed() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        final CheckBox inc = new CheckBox(this);
        inc.setText("On: include the speed hook");
        inc.setChecked(getSharedPreferences("editor", MODE_PRIVATE).getBoolean("patchSpeed", true));
        inc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                getSharedPreferences("editor", MODE_PRIVATE).edit().putBoolean("patchSpeed", inc.isChecked()).apply();
            }
        });
        v.addView(withHelp(inc, "The in-game speed button changes game speed. It only works once the script is installed (Install Script). "
                + "Tap the speed button in the game to pick a speed."));
        v.addView(text("Speeds on the button (comma separated):"));
        final EditText sp = new EditText(this);
        sp.setSingleLine(true);
        sp.setText(getSharedPreferences("editor", MODE_PRIVATE).getString("speeds", "1,2,3,5,10"));
        v.addView(sp);
        final android.widget.Switch tog = new android.widget.Switch(this);
        tog.setText("Floating speed button  ");
        tog.setTextColor(Ui.CREAM);
        tog.setChecked(getSharedPreferences("editor", MODE_PRIVATE).getBoolean("speedAuto", false));
        tog.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                if (!tog.isChecked()) {
                    getSharedPreferences("editor", MODE_PRIVATE).edit().putBoolean("speedAuto", false).apply();
                    stopService(new Intent(MainActivity.this, SpeedService.class));
                    speedState.setText("Off. The speed button will not appear.");
                    ui.postDelayed(new Runnable() { public void run() { ensureHelper(); } }, 700);
                    return;
                }
                getSharedPreferences("editor", MODE_PRIVATE).edit().putString("speeds", sp.getText().toString())
                        .putBoolean("speedAuto", true).apply();
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    tog.setChecked(false);
                    toast("Allow \"Display over other apps\" for FFBE Kupo Forge, then come back and switch it on again.");
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                    return;
                }
                // Android 13+: the "speed button is on" notification needs this permission. The button works without it.
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
                stopService(new Intent(MainActivity.this, SpeedService.class));
                SpeedService.lastError = null;
                try {
                    startForegroundService(new Intent(MainActivity.this, SpeedService.class));
                } catch (Exception e) {
                    tog.setChecked(false);
                    alert("Speed button did not start", "Android refused to start it: " + e);
                    return;
                }
                speedState.setText("Starting the speed button...");
                ui.postDelayed(new Runnable() {
                    public void run() {
                        if (SpeedService.running) {
                            speedState.setText("The speed button is on and waiting. It appears (round \"1x\", top-left) once the game is on screen, "
                                    + "and hides when you leave it. Drag it to move it. Open the game and tap it to pick a speed."
                                    + (SpeedService.lastError != null ? "\n\nNote: " + SpeedService.lastError : ""));
                        } else {
                            String why = SpeedService.lastError != null ? SpeedService.lastError
                                    : "It did not start and gave no reason. Take a screenshot of this screen and send it to me.";
                            speedState.setText("The speed button is NOT showing.\n" + why);
                            alert("Speed button is not showing", why);
                        }
                    }
                }, 1200);
            }
        });
        // the old floating speed bubble is switched off: its switch is not shown (the views stay built because onResume still sets their text)
        speedState = text("");
        v.addView(text("Speed scales the whole game clock (battles, animations, menus). Very high speeds can make some scenes hard to follow. "
                + "Speed resets to 1x when the game restarts. The speeds you type above go to the in-game speed button."));
        final TextView res = text("");
        v.addView(button("Test connection to the game", new View.OnClickListener() {
            public void onClick(View x) {
                res.setText("Asking the game...");
                new Thread(new Runnable() {
                    public void run() {
                        String r;
                        try { r = "Game answered: " + Speed.send("?"); } catch (Exception e) { r = "No answer: " + e.getMessage()
                                + "\nOpen the game first (script installed). The original game has no speed hook."; }
                        final String t = r;
                        ui.post(new Runnable() { public void run() { res.setText(t); } });
                    }
                }).start();
            }
        }));
        v.addView(res);
        return v;
    }

    // ------------------------------------------------------------------ patch tab

    /** Click handler for "Export Kupo log": writes the in-game log to the sync folder, or opens the save dialog. */
    private View.OnClickListener exportKupoLogClick(final TextView res) {
        return new View.OnClickListener() {
            public void onClick(View x) {
                res.setText("Copying the in-game patch log to the sync folder...");
                new Thread(new Runnable() {
                    public void run() {
                        String r;
                        try {
                            if (!Sync.useFolder(MainActivity.this)) {
                                // Cloud sync off or no sync folder: read the log, then let the user pick where to save it (Android save dialog)
                                String lg = Speed.qlog(5000);
                                byte[] lb = lg.getBytes("UTF-8");
                                if (lb.length > 600000) {
                                    int from = lb.length - 600000;
                                    while (from < lb.length && lb[from] != 10) from++;
                                    lb = java.util.Arrays.copyOfRange(lb, Math.min(lb.length, from + 1), lb.length);
                                }
                                pendingLog = lb;
                                pendingLogRes = res;
                                ui.post(new Runnable() { public void run() {
                                    res.setText("No sync folder set. Choose where to save the log.");
                                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                                    i.addCategory(Intent.CATEGORY_OPENABLE);
                                    i.setType("text/plain");
                                    i.putExtra(Intent.EXTRA_TITLE, vname("ffbe_log.txt"));
                                    startActivityForResult(i, WRITE_LOG);
                                } });
                                return;
                            }
                            r = exportText("qlog", 5000, "ffbe_log.txt");
                        }
                        catch (Exception e) { r = "Could not export: " + e.getMessage() + "\nInstall the script and open the game, then try again."; }
                        final String t = r;
                        ui.post(new Runnable() { public void run() { res.setText(t); } });
                    }
                }).start();
            }
        };
    }

    private View buildPatch() {
        ScrollView root = new ScrollView(this);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        root.addView(v);
        TextView howT = text("Install Script");
        howT.setTypeface(Typeface.SERIF, Typeface.BOLD);
        howT.setTextColor(Ui.GOLD);
        howT.setTag("keep");
        v.addView(withHelp(howT, "Puts the Kupo Forge features into the game without patching or reinstalling it. "
                + "It needs the developer's game build for Kupo Forge.\n\n"
                + "1. In the game open Settings > Troubleshooting > Third-party tools and turn it on.\n"
                + "2. Close the game completely and open it again.\n"
                + "3. Tap Install Script here. If the game is closed, the app opens it first, waits for it to load, then installs.\n\n"
                + "The script is saved in the game until the date shown under the buttons. It starts by itself every time the game opens, "
                + "even after a restart. Update the app before that date, then tap Install Script again. "
                + "If the game is an older build, the script is not saved: tap Install Script after every game start.\n\n"
                + "Your Patch List ticks are used. If you change them later, tap Install Script, then restart the game."));
        final TextView installRes = text("");
        installRes.setTextSize(13);
        installRes.setText(KupoInstall.status(this));
        LinearLayout top = row();
        addW(top, button("Install Script", new View.OnClickListener() {
            public void onClick(View x) {
                installRes.setText("Sending the script to the game...");
                new Thread(new Runnable() {
                    public void run() {
                        // game closed: open it, wait for its script door (up to 2 min), then install
                        final boolean cold = !KupoInstall.doorOpen() && !KupoInstall.hooksUp();
                        if (cold) ui.post(new Runnable() { public void run() { installRes.setText("The game is closed. Opening it, then installing the script (up to 2 minutes). You can switch to the game."); } });
                        final String r = cold ? KupoInstall.startAndInstall(MainActivity.this, optJson()) : KupoInstall.run(MainActivity.this, optJson());
                        ui.post(new Runnable() { public void run() { installRes.setText(r); toast(r.length() > 120 ? r.substring(0, 117) + "..." : r); } });
                    }
                }).start();
            }
        }));
        addW(top, button("Export Kupo log", exportKupoLogClick(installRes)));
        v.addView(top);
        final Button startBtn = button("Start game", new View.OnClickListener() {
            public void onClick(View x) {
                Intent li = getPackageManager().getLaunchIntentForPackage("com.exvius.offline.forever");
                if (li == null) {
                    toast("The game (com.exvius.offline.forever) is not installed.");
                    return;
                }
                try {
                    startActivity(li);
                } catch (Exception e) {
                    alert("Could not start the game", String.valueOf(e.getMessage()));
                }
            }
        });
        startBtn.setBackground(Ui.greenBg(this));
        v.addView(startBtn);
        final android.widget.Switch msw = new android.widget.Switch(this);
        msw.setText("Show moogle in game  ");
        msw.setTextColor(Ui.CREAM);
        msw.setChecked(getSharedPreferences("editor", MODE_PRIVATE).getBoolean("moogleShow", true));
        msw.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                final boolean on = msw.isChecked();
                getSharedPreferences("editor", MODE_PRIVATE).edit().putBoolean("moogleShow", on).apply();
                new Thread(new Runnable() { public void run() { try { Speed.sendAll("ms " + (on ? 1 : 0), 3000); } catch (Exception ignored) { } } }).start();
            }
        });
        v.addView(withHelp(msw, "Shows or hides the moogle button inside the game. It changes at once while the game runs, no restart. "
                + "Hidden: the button is gone and the panel cannot be opened in the game. Turn this switch back On here in the app to bring it back. "
                + "The Quick party button stays. Note: after you turn it On during a battle or another screen change, the moogle may only appear after the next screen change. "
                + "The choice is remembered."));
        TextView mnote = text("Hidden: turn it back On here. After turning it On in a battle, the moogle may only appear after the next screen change.");
        mnote.setTextSize(12);
        mnote.setTextColor(Ui.DIM);
        v.addView(mnote);
        v.addView(installRes);
        patchLog = text("");
        patchLog.setTypeface(Typeface.MONOSPACE);
        patchLog.setTextSize(12);
        v.addView(patchLog);
        final LinearLayout speed = buildSpeed();
        speed.setVisibility(View.GONE);
        final Button head = button("▸ Speed button (show)", null);
        head.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                boolean open = speed.getVisibility() != View.VISIBLE;
                speed.setVisibility(open ? View.VISIBLE : View.GONE);
                head.setText(open ? "▾ Speed button (hide)" : "▸ Speed button (show)");
            }
        });
        TextView listTitle = text("Patch List");
        listTitle.setTextSize(17);
        listTitle.setTypeface(Typeface.SERIF, Typeface.BOLD);
        listTitle.setTextColor(Ui.GOLD);
        listTitle.setTag("keep");
        listTitle.setPadding(0, dp(18), 0, dp(4));
        v.addView(listTitle);
        View rule = new View(this);
        rule.setBackgroundColor(Ui.GOLD);
        v.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        v.addView(withHelp(text("Tap a patch to see its settings."), "Features that go into the game when you tap Install Script."));
        LinearLayout speedBox = goldBox();
        speedBox.addView(head);
        speedBox.addView(speed);
        v.addView(speedBox);
        v.addView(buildSync());
        v.addView(patchSection("Edit save", "patchLive", "On: add Open Kupo Forge to the menus",
                "Lets you change your save while the game runs. Tap Open Kupo Forge in the floating menu or the moogle menu, this app opens with the live save, "
                + "and applying restarts the game. Off by default. Your choice is remembered for the next Install Script.", null));
        if (Flags.EXP) {
        v.addView(patchSection("Quick party button", "patchQuick",
                "On: add the Quick party button to the Manage Party screen",
                "Draws a gold \"Quick\" button in the game on the Manage Party screen, above the Arena Party button. Tap it to jump straight to the "
                + "party unit picker (up to 5 units, your current party is already ticked). The floating speed button is not needed. If it does nothing, tap "
                + "\"Export Kupo log\" (top of this tab) while the game runs, and send the file.", null));
        final EditText star = new EditText(this);
        star.setSingleLine(true);
        star.setText(getSharedPreferences("editor", MODE_PRIVATE).getString("labelStar", "\u2605"));
        star.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            public void onTextChanged(CharSequence a, int b, int c, int d) { }
            public void afterTextChanged(android.text.Editable e) {
                getSharedPreferences("editor", MODE_PRIVATE).edit().putString("labelStar", e.toString()).apply();
            }
        });
        LinearLayout starRow = row();
        starRow.setGravity(Gravity.CENTER_VERTICAL);
        starRow.addView(text("Star symbol:"));
        starRow.addView(star, new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));
        v.addView(patchGroup("Summon and Vision", new String[][] {
            {"patchSum", "Skip summon animation", "On: skip the summon animation and the one-by-one unit reveal",
                "The game's tap is pressed for you whenever the summon animation waits for a tap, and the unit list opens already skipped, so units show at once.", null},
            {"patchVsum", "Skip Vision summon", "On: skip the Vision summon animation",
                "Presses the game's own tap on the Vision card reveal for you, so it does not wait for taps.", null},
            {"patchVis", "Skip Vision card level-up", "On: skip the Vision card level-up animation",
                "When the Vision card level-up screen appears, the game's own Skip button is pressed for you after a moment. You still see the result.", null}}));
        v.addView(patchGroup("Battle QoL", new String[][] {
            {"patchAuto", "Auto battle", "On: start every battle with Auto switched on",
                "At the start of a battle the game's own Auto flag is switched on. Tap the floating circle to switch it On or Off at any time.", null},
            {"patchRep", "Repeat battle", "On: enter the same quest again after each battle",
                "When the last result screen has been up for a moment, the same quest is started again, using the game's own quest repeat code. "
                + "It repeats until you switch it Off in the floating circle or something stops it, such as not enough energy.", null},
            {"patchRes", "Skip result screens", "On: skip the result animations and tap through the screens",
                "Skips the exp and unit result animations and taps through the result screens for you, so no tapping is needed. "
                + "Tap the floating circle to switch it On or Off.", null}}));
        v.addView(patchSection("Chamber of the Fallen sort", "patchVortex", "On: list the open Chamber of the Fallen fights first, locked ones below",
                "In the Chamber of the Fallen list, the fights you can enter are moved to the top and the chained (locked) ones go below, "
                + "in the same order as before inside each group. Tap Install Script and restart the game after changing this.", null));
        v.addView(patchGroup("Monster Guide", new String[][] {
            {"patchGuide", "Monster Guide unlock", "On: the game's Monster Guide shows the monsters you choose in the Bestiary tab",
                "When the game loads the Monster Guide data, it is filled from the Bestiary tab choice in the save (monsters are shown as seen). "
                + "If you never used the Bestiary tab, every monster is shown. Use Apply to game in the Save Editor tab to send a new choice. "
                + "Tap Install Script and restart the game after changing this switch.",
                null}}));
        v.addView(patchGroup("Gambit", new String[][] {
            {"patchBot", "Gambit", "On: add a Gambit switch to the floating circle",
                "An auto-cast bot. With the Gambit switch On (floating circle or moogle menu), it opens each unit's Ability menu one by one. "
                + "It first checks that unit's rules from the Gambit tab (top rule that fits wins). If no rule fits, it picks a skill itself "
                + "(a buff on the first turn, then the strongest damage skill; heals and scans are skipped). "
                + "It then taps all units at once so their actions start together, and repeats on the next turn and stage. "
                + "Gambit starts Off every launch. Details go to the log (lines start with Gambit or Bot test). "
                + "Tap Install Script and restart the game after changing this.",
                null},
            {"patchOvNote", "Gambit notice (floating)", "On: show what Gambit chose in a small floating box",
                "A separate Android window, drawn by Kupo Forge outside the game, so it does not touch the game's own screen. It keeps the last 8 Gambit choices, newest at the bottom, "
                + "for example \"T3 Vaan: rule 3 > Angel Song\". Turn it on in the moogle menu (Gambit float log). Drag it to move it. Tap it to pause or resume. Hold it to change the text size. "
                + "Needs the Gambit switch above and \"Display over other apps\" allowed for Kupo Forge. Tap Install Script and restart the game after changing this.",
                null},
            {"patchDlog", "Decision log", "On: write how Gambit chose each unit's action into the Kupo log",
                "For every unit each turn, one line: turn and stage, the unit, its hp, mp and limit gauge, then every rule in order with the value it looked at "
                + "and yes or no, the rule that fired and the action. Disabled rows show as off. It only reads numbers Gambit already reads. "
                + "Needs the Gambit switch above. Export the Kupo log to read it. Leave it off in normal play.",
                null}}));
        }
        TextView devTitle = text("Developer Only");
        devTitle.setTextSize(17);
        devTitle.setTypeface(Typeface.SERIF, Typeface.BOLD);
        devTitle.setTextColor(Ui.GOLD);
        devTitle.setTag("keep");
        devTitle.setPadding(0, dp(18), 0, dp(4));
        v.addView(devTitle);
        View devRule = new View(this);
        devRule.setBackgroundColor(Ui.GOLD);
        v.addView(devRule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        final LinearLayout dev = buildDev();
        dev.setVisibility(View.GONE);
        final Button devHead = button("▸ Icons and file tests (show)", null);
        devHead.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) {
                boolean open = dev.getVisibility() != View.VISIBLE;
                dev.setVisibility(open ? View.VISIBLE : View.GONE);
                devHead.setText(open ? "▾ Icons and file tests (hide)" : "▸ Icons and file tests (show)");
            }
        });
        if (Flags.LOGS) dev.addView(patchSection("Reward probe (test)", "patchRprobe", "On: log what the battle rewards look like (test, changes nothing)",
                "For the planned reward multiplier. It only writes lines to the Kupo log: every drop the battle adds (item id, type, count), the gil total and the item list the game "
                + "reports when a battle ends. Turn it on, tap Install Script, restart the game, win one battle, then export the Kupo log and send it. Nothing is changed.", null));
        LinearLayout devBox = goldBox();
        devBox.addView(devHead);
        devBox.addView(dev);
        v.addView(devBox);
        v.addView(patchSection("Landscape mode", "patchLand",
                "On: let the game turn sideways (does not work with Install Script yet)",
                "The game keeps its tall portrait shape, centred, with black bars left and right (not stretched). Good for tablets. "
                + "Turn your phone's rotation on, then turn the phone. Off keeps the game portrait only. "
                + "The speed button follows the game strip. In portrait nothing changes.", null));
        InstallReceiver.listener = new InstallReceiver.Listener() {
            public void result(final String t) {
                ui.post(new Runnable() {
                    public void run() {
                        if (patched != null && !patched.exists()) patched = null;
                        log(t);
                    }
                });
            }
        };
        // Leftovers from a run that was killed part-way.
        new File(getCacheDir(), "patched.unsigned").delete();
        new File(getCacheDir(), "source.apk").delete();
        File old = new File(getCacheDir(), "patched.apk");
        if (old.exists()) patched = old;
        return root;
    }

    private V2Signer signer() throws Exception {
        return new V2Signer(getAssets().open("editor.pk8"), getAssets().open("editor.crt"));
    }

    private void log(final String s) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(new Runnable() { public void run() { log(s); } });
            return;
        }
        patchLog.append(s + "\n");
    }

    private void patchInstalled() {
        if (busy) return;
        PackageManager pm = getPackageManager();
        final List<ApplicationInfo> games = new ArrayList<ApplicationInfo>();
        List<String> labels = new ArrayList<String>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ai.sourceDir == null) continue;
            if (ai.packageName.equals(getPackageName())) continue;
            try {
                if (!Patcher.hasScript(new File(ai.sourceDir))) continue;
            } catch (Exception e) {
                continue;
            }
            games.add(ai);
            labels.add(pm.getApplicationLabel(ai) + "  (" + ai.packageName + ")");
        }
        if (games.isEmpty()) {
            alert("Game not found", "The FFBE Forever game is not installed.");
            return;
        }
        new Dlg(this).setTitle("Pick the game")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ApplicationInfo ai = games.get(w);
                        if (ai.splitSourceDirs != null && ai.splitSourceDirs.length > 0) {
                            alert("Split install", "This game is installed as several APK parts. Patch the original APK file instead.");
                            return;
                        }
                        startPatch(new File(ai.sourceDir), null);
                    }
                }).show();
    }

    /** The in-game options from the saved Patch List ticks, as one JSON object (also valid JS). Used by the patch and by Install Script. */
    static String optJson(android.content.Context c) {
        SharedPreferences pf = c.getSharedPreferences("editor", MODE_PRIVATE);
        boolean wq = Flags.EXP && pf.getBoolean("patchQuick", false);
        boolean wd = false;   // Landscape needs a game restart setting the script cannot give (screen turning); never sent by Install Script
        boolean wv = Flags.EXP && pf.getBoolean("patchVis", false);
        boolean wvs = Flags.EXP && pf.getBoolean("patchVsum", false);
        String[] ek = {"patchSum", "patchAuto", "patchRep", "patchRes", "patchCloud", "patchLive", "patchBot", "patchBot", "patchGuide", "patchVortex"};
        String[] en = {"sum", "auto", "rep", "res", "cloud", "live", "bot", "botopen", "guide", "vortex"};
        StringBuilder more = new StringBuilder();
        for (int i = 0; i < ek.length; i++) {
            boolean on = Flags.EXP && pf.getBoolean(ek[i], false) && (Flags.BOT || !(ek[i].startsWith("patchBot") || ek[i].equals("patchGuide")));
            more.append(",\"").append(en[i]).append("\":").append(on ? 1 : 0);
        }
        more.append(",\"mshow\":").append(pf.getBoolean("moogleShow", true) ? 1 : 0);
        more.append(",\"onote\":").append(Flags.LOGS && pf.getBoolean("patchOvNote", false) ? 1 : 0);
        more.append(",\"rprobe\":").append(Flags.LOGS && pf.getBoolean("patchRprobe", false) ? 1 : 0);
        more.append(",\"dlog\":").append(Flags.LOGS && pf.getBoolean("patchDlog", false) ? 1 : 0);
        String star = pf.getString("labelStar", "\u2605").trim();
        if (star.length() == 0) star = "\u2605";
        String appVer = "";
        try { appVer = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        return "{\"ver\":" + org.json.JSONObject.quote(appVer) + ",\"quick\":" + (wq ? 1 : 0) + ",\"label\":0,\"land\":" + (wd ? 1 : 0) + ",\"vis\":" + (wv ? 1 : 0)
                + ",\"vsum\":" + (wvs ? 1 : 0) + more + ",\"speeds\":" + speedsJs(c)
                + ",\"name\":" + (pf.getBoolean("labelName", false) ? 1 : 0) + ",\"star\":" + org.json.JSONObject.quote(star) + "}";
    }
    private String optJson() { return optJson(this); }

    private void startPatch(final File src, final Uri uri) {
        busy = true;
        patchLog.setText("");
        log("Working. A 180 MB game takes a minute or two and needs about 400 MB free while it runs.");
        new Thread(new Runnable() {
            public void run() {
                File copy = uri != null ? new File(getCacheDir(), "source.apk") : null;
                File tmp = new File(getCacheDir(), "patched.unsigned");
                File out = new File(getCacheDir(), "patched.apk");
                out.delete();                 // an older patched copy is replaced
                patched = null;
                boolean ok = false;
                try {
                    File in = src;
                    if (copy != null) {
                        log("Copying the APK file...");
                        InputStream s = getContentResolver().openInputStream(uri);
                        OutputStream o = new FileOutputStream(copy);
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = s.read(buf)) > 0) o.write(buf, 0, n);
                        o.close();
                        s.close();
                        in = copy;
                    }
                    log("Already patched before: " + (Patcher.isPatched(in) ? "yes (the hook will be replaced)" : "no"));
                    SharedPreferences pf = getSharedPreferences("editor", MODE_PRIVATE);
                    boolean wq = Flags.EXP && pf.getBoolean("patchQuick", false), wl = false;   // Unit label is switched off for now (under development)
                    boolean wd = Flags.EXP && pf.getBoolean("patchLand", false);
                    boolean wv = Flags.EXP && pf.getBoolean("patchVis", false);
                    boolean wvs = Flags.EXP && pf.getBoolean("patchVsum", false);
                    String[] ek = {"patchSum", "patchAuto", "patchRep", "patchRes", "patchCloud", "patchLive", "patchBot", "patchBot", "patchGuide"};   // Gambit is one switch: it turns on both the bot and its auto-open (old saved settings keep working)
                    String[] en = {"sum", "auto", "rep", "res", "cloud", "live", "bot", "botopen", "guide"};
                    StringBuilder more = new StringBuilder();
                    boolean wm = false;
                    for (int i = 0; i < ek.length; i++) {
                        boolean on = Flags.EXP && pf.getBoolean(ek[i], false) && (Flags.BOT || !(ek[i].startsWith("patchBot") || ek[i].equals("patchGuide")));
                        if (on) wm = true;
                        more.append(",").append(en[i]).append(":").append(on ? 1 : 0);
                    }
                    more.append(",mshow:").append(getSharedPreferences("editor", MODE_PRIVATE).getBoolean("moogleShow", true) ? 1 : 0);
                    more.append(",onote:").append(Flags.LOGS && pf.getBoolean("patchOvNote", false) ? 1 : 0);
                    more.append(",rprobe:").append(Flags.LOGS && pf.getBoolean("patchRprobe", false) ? 1 : 0);
                    more.append(",dlog:").append(Flags.LOGS && pf.getBoolean("patchDlog", false) ? 1 : 0);
                    Patcher.orientation = wd ? 2 : 1;
                    boolean ws = pf.getBoolean("patchSpeed", true);
                    byte[] hook = (ws || wq || wl || wd || wv || wvs || wm)
                            ? readAll(getAssets().open("speed_patch.js"))
                            : (Patcher.MARK + "\n").getBytes("UTF-8");
                    if (true) {   // the title screen brand is always on, so the quick patch file always goes in
                        String opt = "\n;globalThis.FFBE_OPT=" + optJson() + ";\n";
                        java.io.ByteArrayOutputStream hb = new java.io.ByteArrayOutputStream();
                        hb.write(hook);
                        hb.write(opt.getBytes("UTF-8"));
                        hb.write(readAll(getAssets().open("quick_patch.js")));
                        hook = hb.toByteArray();
                        log("In-game patches: " + (wq ? "Quick party " : "") + (wl ? "Unit label " : "") + (wd ? "Landscape " : "") + (wv ? "Skip Vision card level-up " : "") + (wvs ? "Skip Vision summon " : "") + (wm ? "other skips (see switches)" : ""));
                    }
                    log("Speed hook: " + (hook.length > 100 ? "On" : "Off (marker only)"));
                    Patcher.Extra extra = null;
                    if (false) {   // Export folder picker removed (sync folder does the job)
                        extra = new Patcher.Extra();
                        extra.dex7 = readAll(getAssets().open("gp/classes7.dex"));
                        extra.dex8 = readAll(getAssets().open("gp/classes8.dex"));
                        extra.origSha = new String(readAll(getAssets().open("gp/classes7.sha256")), "UTF-8").trim();
                    } else log("Folder picker: Off");
                    Patcher.rebuild(in, tmp, hook, extra, new Patcher.Log() {
                        public void line(String s) { log(s); }
                    });
                    if (copy != null) copy.delete();      // free space before signing
                    log("Signing with the editor key...");
                    signer().sign(tmp, out);
                    patched = out;
                    ok = true;
                    log("Done: " + (out.length() / (1024 * 1024)) + " MB. Tap \"Install patched game\". "
                            + "The patched copy is deleted after a successful install.");
                } catch (Throwable e) {
                    log("FAILED: " + e.getMessage());
                } finally {
                    tmp.delete();
                    if (copy != null) copy.delete();
                    if (!ok) out.delete();
                    busy = false;
                }
            }
        }).start();
    }

    private boolean canInstall() {
        if (getPackageManager().canRequestPackageInstalls()) return true;
        toast("Allow FFBE Kupo Forge to install apps, then come back and tap Install again.");
        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        return false;
    }

    private void install() {
        if (patched == null || !patched.exists()) { toast("Patch the game first."); return; }
        if (!canInstall()) return;
        installFrom(null);
    }

    /** Installs the patched copy (uri == null) or an APK file the user picked. */
    private void installFrom(final Uri uri) {
        log("Starting the install...");
        new Thread(new Runnable() {
            public void run() {
                try {
                    PackageInstaller pi = getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    long size = uri == null ? patched.length() : -1;
                    if (size > 0) sp.setSize(size);
                    int id = pi.createSession(sp);
                    PackageInstaller.Session s = pi.openSession(id);
                    OutputStream o = s.openWrite("game.apk", 0, size);
                    InputStream in = uri == null ? new FileInputStream(patched) : getContentResolver().openInputStream(uri);
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    in.close();
                    s.fsync(o);
                    o.close();
                    Intent cb = new Intent(MainActivity.this, InstallReceiver.class);
                    PendingIntent p = PendingIntent.getBroadcast(MainActivity.this, id, cb,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    s.commit(p.getIntentSender());
                    s.close();
                    log("Waiting for you to confirm the install...");
                } catch (Exception e) {
                    log("Install failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private void copyPatched(Uri u) {
        try {
            OutputStream o = getContentResolver().openOutputStream(u, "wt");
            InputStream in = new FileInputStream(patched);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            in.close();
            o.close();
            log("Saved the patched APK to " + displayName(u) + ".");
        } catch (Exception e) {
            log("Could not save: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ results

    protected void onPause() {
        super.onPause();
        SpeedService.editorFront = false;
    }

    /** The helper service passes cloud sync, Edit save, Gambit rules and the log between the game and the app. It needs no overlay permission. */
    private void ensureHelper() {
        if (SpeedService.running) return;
        try { startForegroundService(new Intent(this, SpeedService.class)); } catch (Exception ignored) { }
    }

    protected void onResume() {
        super.onResume();
        autoLog();
        ui.postDelayed(new Runnable() { public void run() { ensureHelper(); } }, 800);
        SpeedService.editorFront = true;
        if (ed != null && getSharedPreferences("editor", MODE_PRIVATE).getBoolean("gambitPicked", false)) {   // a preset was chosen in the game: follow it
            getSharedPreferences("editor", MODE_PRIVATE).edit().putBoolean("gambitPicked", false).apply();
            try {
                File pf = new File(getFilesDir(), "gambit_presets.json");
                Object po = Json.parse(new String(readAll(new FileInputStream(pf)), "UTF-8"));
                if (po instanceof Map) { ed.presetSelect(String.valueOf(((Map<String, Object>) po).get("active"))); if (gambit != null) gambit.refresh(); }
            } catch (Exception ignored) { }
        }
        if (speedState != null && !SpeedService.running) {
            boolean can = Settings.canDrawOverlays(this);
            if (can && getSharedPreferences("editor", MODE_PRIVATE).getBoolean("speedAuto", false)) {
                try { startForegroundService(new Intent(this, SpeedService.class)); } catch (Exception ignored) { }
                speedState.setText("The speed button is on and waiting for the game.");
            } else {
                speedState.setText(can
                        ? "\"Display over other apps\" is allowed. Switch the floating speed button on."
                        : "\"Display over other apps\" is NOT allowed yet. Switch the floating speed button on to allow it.");
            }
        }
    }

    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == WRITE_LOG && (res != RESULT_OK || data == null || data.getData() == null)) {
            pendingLog = null;
            if (pendingLogRes != null) pendingLogRes.setText("Log not saved (no place chosen).");
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (req == WRITE_LOG) {
            try {
                OutputStream o = getContentResolver().openOutputStream(u, "wt");
                o.write(pendingLog);
                o.close();
                String m = "Log saved to " + displayName(u) + " (" + pendingLog.length + " bytes).";
                toast(m);
                if (pendingLogRes != null) pendingLogRes.setText(m + "\n" + u);
            } catch (Exception e) { alert("Could not write the log", String.valueOf(e.getMessage())); }
            pendingLog = null;
            return;
        }
        if (req == OPEN_SAVE) loadSave(u);
        else if (req == WRITE_SAVE) writeSave(u);
        else if (req == OPEN_APK) startPatch(null, u);
        else if (req == WRITE_APK) copyPatched(u);
        else if (req == INSTALL_APK) { patchLog.setText(""); installFrom(u); }
        else if (req == PICK_ICONS) importIcons(u);
        else if (req == WRITE_TABLES) writeTables(u);
        else if (req == WRITE_GAMBIT) {
            try {
                OutputStream o = getContentResolver().openOutputStream(u, "wt");
                o.write(pendingGambit.getBytes("UTF-8"));
                o.close();
                toast("Gambit saved to " + displayName(u));
            } catch (Exception e) { alert("Could not write the file", String.valueOf(e.getMessage())); }
        } else if (req == OPEN_GAMBIT) {
            try {
                String t = new String(readAll(getContentResolver().openInputStream(u)), "UTF-8");
                int n = ed.gambitImport(t);
                ed.dirty = true;
                updateStatus();
                gambit.refresh();
                toast("Imported gambit rules for " + n + " characters");
            } catch (Exception e) { alert("Could not import", String.valueOf(e.getMessage())); }
        }
        else if (req == SYNC_TREE) {
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) { }
            Sync.setTree(this, u);
            if (syncStatus != null) syncStatus.setText("Sync folder set.");
            syncRefresh();
        }
    }

    private void refreshLists() {
        if (unitsFill != null) unitsFill.run();
        if (itemsFill != null) itemsFill.run();
    }

    private void importIcons(final Uri tree) {
        final TextView t = impText;
        if (t == null) return;
        t.setText("Importing icons...");
        new Thread(new Runnable() {
            public void run() {
                String r;
                try {
                    r = Icons.importTree(MainActivity.this, tree, db, new Icons.Progress() {
                        public void step(final String s) { ui.post(new Runnable() { public void run() { t.setText(s); } }); }
                    });
                } catch (Throwable e) {
                    r = "Import failed: " + e;
                }
                final String res = r;
                ui.post(new Runnable() { public void run() { t.setText(res); refreshLists(); } });
            }
        }).start();
    }
}
