package com.ffbeforever.saveedit;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Gambit tab: per-character rules for the battle bot. Each rule has a condition, a comparison, a number and an action. Rules are
 * checked from the top; the first one that fits is used, otherwise the bot's general rule decides. Stored in the save (key "gambit").
 */
@SuppressWarnings("unchecked")
final class Gambit {
    interface Host {
        Editor ed();
        Db db();
        void changed();
        void startExport(String json);
        void startImport();
        void apply();
    }

    // FFXII style conditions (id, label). The id is what the battle bot will match.
    static final List<String[]> CONDS = new ArrayList<String[]>();
    static {
        c("always", "Always");
        c("status", "Status");
        c("ally_ko", "Ally: KO'd count");
        c("foe_left", "Foe: enemies left count");
        c("hp_pct", "HP %");
        c("hp_amt", "HP Amount");
        c("avg_hp_pct", "Average HP %");
        c("avg_hp_amt", "Average HP");
        c("lb", "Limit Gauge");
        c("mp_pct", "MP %");
        c("mp_amt", "MP");
        c("summon_pct", "Summon Gauge %");
        for (String w : new String[]{"Foe", "Ally"})
            for (String m : new String[]{"Highest", "Lowest"}) {
                c(w.toLowerCase() + "_" + m.toLowerCase() + "_hp", w + ": " + m + " HP");
                c(w.toLowerCase() + "_" + m.toLowerCase() + "_maxhp", w + ": " + m + " Max HP");
                c(w.toLowerCase() + "_" + m.toLowerCase() + "_lvl", w + ": " + m + " Level");
            }
        c("elem_res", "Element Resistance");
        c("stat_res", "Status Resistance");
        c("foe_race", "Foe: Race");
        c("turn_1", "Turn: first turn");
        c("turn_even", "Turn: even turns");
        c("turn_odd", "Turn: odd turns");
        c("turn_mod", "Turn y of an x-turn rotation");
    }

    /** Conditions turned off for now: Foe race and Status Resistance (not working yet) and every Highest / Lowest pick (the game picks the target itself, so they never worked). */
    static boolean offCond(String id) {
        return id.matches("(foe|ally)_(highest|lowest)_.*");
    }

    /** True when the rule (or any condition inside it) uses a turned-off condition. Such rules stay in the file but are not sent to the game. */
    static boolean usesOff(Object v) {
        if (v instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (e.getKey().equals("c") && e.getValue() instanceof String && offCond((String) e.getValue())) return true;
                if (usesOff(e.getValue())) return true;
            }
        } else if (v instanceof List) {
            for (Object o : (List<Object>) v) if (usesOff(o)) return true;
        }
        return false;
    }

    static final String[] OPS = {">", ">=", "=", "<=", "<"};
    static final String[] ELEMENTS = {"All elements", "Fire", "Ice", "Lightning", "Water", "Wind", "Earth", "Light", "Dark"};
    static final String[] RES_STATUS = {"All status resistances", "Poison", "Blind", "Sleep", "Silence", "Paralysis", "Confusion", "Disease", "Petrify", "Stop", "Charm", "Berserk"};
    static final String[] RACES = {"Aquan", "Beast", "Bird", "Bug", "Demon", "Dragon", "Human", "Machine", "Plant", "Spirit", "Stone", "Undead"};

    /** Which extra fields a condition type shows: status, count, val2 (Self/Ally), val3 (Self/Ally/Foe), elem, sres, race, turn or none. */
    static String kind(String id) {
        if (id.equals("status")) return "status";
        if (id.equals("ally_ko") || id.equals("foe_left") || id.equals("summon_pct")) return "count";
        if (id.equals("lb") || id.equals("mp_pct") || id.equals("mp_amt")) return "val2";
        if (id.equals("hp_pct") || id.equals("hp_amt") || id.equals("avg_hp_pct") || id.equals("avg_hp_amt")) return "val3";
        if (id.equals("elem_res")) return "elem";
        if (id.equals("stat_res")) return "sres";
        if (id.equals("foe_race")) return "race";
        if (id.equals("turn_mod")) return "turn";
        return "none";
    }

    /** Turns a condition saved by an older version (one fixed id per case) into the new fields. Safe to call again. */
    static void migrate(Map<String, Object> m) {
        String c = Editor.str(m.get("c"));
        String[] old = null;     // {type, target, op, n}
        int cl = c.indexOf(':');
        String p = cl < 0 ? "" : c.substring(cl + 1);
        String b = cl < 0 ? c : c.substring(0, cl);
        if (b.equals("ally_status")) old = new String[]{"status", "ally", "is", ""};
        else if (b.equals("ally_nostatus")) old = new String[]{"status", "ally", "isnot", ""};
        else if (b.equals("foe_status")) old = new String[]{"status", "foe", "is", ""};
        else if (b.equals("foe_nostatus")) old = new String[]{"status", "foe", "isnot", ""};
        else if (b.equals("self_status")) old = new String[]{"status", "self", "is", ""};
        else if (b.equals("self_nostatus")) old = new String[]{"status", "self", "isnot", ""};
        else if (b.equals("ally_dead")) old = new String[]{"ally_ko", "", ">=", "1"};
        else if (b.equals("ally_dead2")) old = new String[]{"ally_ko", "", ">=", "2"};
        else if (b.equals("ally_hp_lt")) old = new String[]{"hp_pct", "ally", "<", p};
        else if (b.equals("foe_count1")) old = new String[]{"foe_left", "", "<=", "1"};
        else if (b.equals("foe_count2")) old = new String[]{"foe_left", "", ">=", "2"};
        else if (b.equals("foe_count3")) old = new String[]{"foe_left", "", ">=", "3"};
        else if (b.equals("foe_hp_lt")) old = new String[]{"hp_pct", "foe", "<", p};
        else if (b.equals("foe_hp_gt")) old = new String[]{"hp_pct", "foe", ">", p};
        else if (b.equals("party_hp_lt")) old = new String[]{"avg_hp_pct", "ally", "<", p};
        else if (b.equals("self_hp_full")) old = new String[]{"hp_pct", "self", "=", "100"};
        else if (b.equals("self_hp_lt")) old = new String[]{"hp_pct", "self", "<", p};
        else if (b.equals("self_lb_full")) old = new String[]{"lb", "self", "=", "100"};
        else if (b.equals("self_lb_lt")) old = new String[]{"lb", "self", "<", p};
        else if (b.equals("self_mp_lt")) old = new String[]{"mp_pct", "self", "<", p};
        else if (b.equals("self_mp_gt")) old = new String[]{"mp_pct", "self", ">", p};
        if (old == null) return;
        m.put("c", old[0]);
        if (!old[1].isEmpty()) m.put("t", old[1]);
        m.put("op", old[2]);
        if (!old[3].isEmpty()) m.put("n", old[3]);
    }


    /** Status names offered in the dropdown, grouped. Buffs and debuffs map to game buff types (gambit_status_samples.md); ailments use the game's bad-state list. */
    static final String[] AILMENTS = {"Poison", "Blind", "Sleep", "Silence", "Paralysis", "Confusion", "Disease", "Petrify", "Berserk", "Charm", "Death"};
    static final String[] BUFFS = {
        "ATK up", "DEF up", "MAG up", "SPR up", "Regen", "Auto-Regen", "MP regen", "Reraise", "Provoke", "Taunt", "Reflect", "Physical damage resist up", "Magic damage resist up", "Mitigation up", "Physical evade up", "Cover", "Counter", "Dual cast", "Limit gauge boost", "LB damage up", "Physical killer up", "Magic killer up", "Imbue", "Fire imbue", "Fire resist up", "Ice imbue", "Ice resist up", "Lightning imbue", "Lightning resist up", "Water imbue", "Water resist up", "Wind imbue", "Wind resist up", "Earth imbue", "Earth resist up", "Light imbue", "Light resist up", "Dark imbue", "Dark resist up", "Poison resist up", "Blind resist up", "Sleep resist up", "Silence resist up", "Paralysis resist up", "Confusion resist up", "Disease resist up", "Petrify resist up", "Charm resist up", "Stop resist up", "Death resist up", "Accuracy up", "Magic accuracy up", "Haste", "Protect", "Shell", "Reduced damage taken"
    };
    static final String[] DEBUFFS = {
        "ATK down", "ATK break", "DEF down", "DEF break", "MAG down", "MAG break", "SPR down", "SPR break", "Stop", "Fire resist down", "Imperil Fire", "Ice resist down", "Imperil Ice", "Lightning resist down", "Imperil Lightning", "Water resist down", "Imperil Water", "Wind resist down", "Imperil Wind", "Earth resist down", "Imperil Earth", "Light resist down", "Imperil Light", "Dark resist down", "Imperil Dark", "Imperil (all elements)", "Accuracy down", "Magic accuracy down", "Slow", "Damage over time", "Increased damage taken"
    };
    static final String[] STATUSES = concat(AILMENTS, BUFFS, DEBUFFS);
    private static String[] concat(String[]... a) {
        int n = 0;
        for (String[] x : a) n += x.length;
        String[] r = new String[n];
        int i = 0;
        for (String[] x : a) { System.arraycopy(x, 0, r, i, x.length); i += x.length; }
        return r;
    }

    private static void c(String id, String label) { CONDS.add(new String[]{id, label}); }

    private final Context cx;
    private final Host host;
    private final LinearLayout root, listHost;
    private final TextView info;
    private Button presetBtn;

    private void nameDialog(String title, String def, final boolean copy) {
        final EditText in = new EditText(cx);
        in.setText(def);
        in.setHint("Preset name");
        in.setSingleLine(true);
        new Dlg(cx).setTitle(title).setView(in)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        host.ed().presetNew(in.getText().toString(), copy);
                        open.clear();
                        host.changed();
                        refresh();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void renameDialog() {
        final EditText in = new EditText(cx);
        in.setText(host.ed().presetActive());
        in.setSingleLine(true);
        new Dlg(cx).setTitle("Rename preset").setView(in)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        host.ed().presetRename(in.getText().toString());
                        host.changed();
                        refresh();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void deletePreset() {
        if (host.ed().presetNames().size() < 2) {
            Toast.makeText(cx, "The last preset cannot be deleted.", Toast.LENGTH_SHORT).show();
            return;
        }
        new Dlg(cx).setTitle("Delete preset").setMessage("Delete \"" + host.ed().presetActive() + "\" and all its rules?")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        host.ed().presetDelete();
                        open.clear();
                        host.changed();
                        refresh();
                    }
                }).setNegativeButton("Cancel", null).show();
    }
    private final Set<String> open = new HashSet<String>();

    Gambit(Context c, Host h) {
        cx = c;
        host = h;
        ScrollView sv = new ScrollView(c);
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        sv.addView(root);
        scroll = sv;
        info = text("");
        info.setTextSize(12);
        // top row: Apply to game on the left, gear and ? on the right
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(button("Send to game", new View.OnClickListener() {
            public void onClick(View v) { host.apply(); }
        }));
        bar.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        if (Flags.LOGS) bar.addView(button("Decision log", new View.OnClickListener() {
            public void onClick(View v) { DecisionLog.open(cx); }
        }));
        bar.addView(miniBtn(miniText("\u2699", 14), new View.OnClickListener() {
            public void onClick(View v) { if (needSave()) gearDialog(); }
        }), miniLp());
        bar.addView(roundHelp(new View.OnClickListener() {
            public void onClick(View v) {
                new Dlg(cx).setTitle("Gambit")
                        .setMessage("Every change goes into the active preset right away and is sent to the running game within a second or two, so no restart is needed. The next unit that acts uses the new rules. The app also keeps its own copy of your presets. Send to game sends them again at once, without a restart. To store them inside the save file too, use Apply to game in the Save Editor tab (the game restarts) or Save edited copy. "
                                + "In battle the game uses these rules when the Gambit switch is on (Patch List, then the floating circle or moogle menu).\n\nEach row: when [condition], do [action]. Rules at the top are checked first; if none fits, the general auto-cast decides. "
                                + "Up to " + Editor.GAMBIT_MAX + " rules per character. Use the number button on a row to move it to another position."
                                + (Flags.LOGS ? "\n\nDecision log opens a list of what Gambit chose each turn (turn on the Decision log switch in the Patch tab first)." : "") + "\n\nThe gear button holds the presets (New, Duplicate, Rename, Delete) and Import / Export gambit.")
                        .setPositiveButton("OK", null).show();
            }
        }), roundLp());
        root.addView(bar);
        listHost = new LinearLayout(c);
        listHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(listHost);
        root.addView(button("Add character", new View.OnClickListener() {
            public void onClick(View v) { if (needSave()) pickCharacter(); }
        }));
        root.addView(info);
    }

    private final ScrollView scroll;

    private void pickItem(final Map<String, Object> rule, final Button ba) {
        SkillNames.init(cx);
        final Editor ed = host.ed();
        final List<String> ids = new ArrayList<String>();
        for (String k : SkillNames.iNames.keySet()) if (SkillNames.iBattle.isEmpty() || SkillNames.iBattle.contains(k)) ids.add(k);
        Collections.sort(ids, new Comparator<String>() {
            public int compare(String a, String b) {
                String x = SkillNames.iNames.get(a), y = SkillNames.iNames.get(b);
                return x.compareToIgnoreCase(y);
            }
        });
        if (ids.isEmpty()) {
            askId("Item id", "Item names are not loaded yet. Type the item id", new Pick2() {
                public void on(String t) { rule.put("a", "item:" + t); rule.remove("an"); ba.setText(actionText(rule) + "  \u25be"); host.changed(); }
            });
            return;
        }
        final List<String> labels = new ArrayList<String>(), descs = new ArrayList<String>();
        final List<Bitmap> icons = new ArrayList<Bitmap>();
        for (String id : ids) {
            long have = 0;
            try { long k = Long.parseLong(id); have = ed.owned(20, k) + ed.owned(23, k); } catch (Exception ignored) { }
            labels.add(SkillNames.iNames.get(id) + "  x" + have);
            String ds = SkillNames.iDesc.get(id);
            descs.add(ds == null ? "" : ds);
            icons.add(SkillNames.itemIcon(id));
        }
        searchRich("Use item", labels, descs, icons, new Pick() {
            public void on(int i) {
                rule.put("a", "item:" + ids.get(i));
                rule.put("an", SkillNames.iNames.get(ids.get(i)));
                ba.setText(actionText(rule) + "  \u25be");
                host.changed();
            }
        });
    }

    private final java.util.Map<String, Bitmap> abm = new java.util.HashMap<String, Bitmap>();

    /** A picture bundled in the APK (assets/<name>), cached. */
    private Bitmap asset(String name) {
        Bitmap b = abm.get(name);
        if (b == null) {
            try {
                java.io.InputStream in = cx.getAssets().open(name);
                b = android.graphics.BitmapFactory.decodeStream(in);
                in.close();
                if (b != null) abm.put(name, b);
            } catch (Exception ignored) { }
        }
        return b;
    }

    private Bitmap lbBmp;

    /** The Limit Burst picture bundled in the APK. */
    private Bitmap lbIcon() {
        if (lbBmp == null) {
            try {
                java.io.InputStream in = cx.getAssets().open("lb_icon.png");
                lbBmp = android.graphics.BitmapFactory.decodeStream(in);
                in.close();
            } catch (Exception ignored) { }
        }
        return lbBmp;
    }

    /** The game's own icon of a sword (slot Weapon) or shield (slot Shield), taken from the bundled gear icons. */
    private Bitmap gearIcon(String slot) {
        Db db = host.db();
        if (db == null) return null;
        for (Db.Item it : db.items) {
            if (it.kind != 21 || !slot.equals(it.slot)) continue;
            if (slot.equals("Weapon") && !it.type.contains("Sword")) continue;
            Bitmap b = Icons.item(it);
            if (b != null) return b;
        }
        return null;
    }

    private long namesTry;

    /** Reads the skill names from the running game when the tab opens (once a minute at most, only when not cached yet). */
    void autoLoadNames() {
        SkillNames.init(cx);
        long now = System.currentTimeMillis();
        if (SkillNames.hasNames() || now - namesTry < 60000) return;
        namesTry = now;
        new Thread(new Runnable() {
            public void run() {
                try { SkillNames.fetch(cx); } catch (Exception ignored) { }
            }
        }).start();
    }

    /** Presets, import and export, behind the gear button. */
    private void gearDialog() {
        final AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout box = new LinearLayout(cx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), 0);
        presetBtn = button("Preset: " + host.ed().presetActive() + "  \u25be", new View.OnClickListener() {
            public void onClick(View v) {
                final List<String> n = host.ed().presetNames();
                search("Choose preset", n, new Pick() {
                    public void on(int i) { host.ed().presetSelect(n.get(i)); open.clear(); host.changed(); refresh(); }
                });
            }
        });
        box.addView(presetBtn);
        LinearLayout pb = new LinearLayout(cx);
        pb.setOrientation(LinearLayout.HORIZONTAL);
        addW(pb, button("New", new View.OnClickListener() {
            public void onClick(View v) { dlg[0].dismiss(); nameDialog("New preset", "", false); }
        }));
        addW(pb, button("Duplicate", new View.OnClickListener() {
            public void onClick(View v) { dlg[0].dismiss(); nameDialog("Duplicate preset", host.ed().presetActive() + " copy", true); }
        }));
        box.addView(pb);
        LinearLayout pc = new LinearLayout(cx);
        pc.setOrientation(LinearLayout.HORIZONTAL);
        addW(pc, button("Rename", new View.OnClickListener() {
            public void onClick(View v) { dlg[0].dismiss(); renameDialog(); }
        }));
        addW(pc, button("Delete", new View.OnClickListener() {
            public void onClick(View v) { dlg[0].dismiss(); deletePreset(); }
        }));
        box.addView(pc);
        LinearLayout top = new LinearLayout(cx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        addW(top, button("Import gambit", new View.OnClickListener() {
            public void onClick(View v) { dlg[0].dismiss(); host.startImport(); }
        }));
        addW(top, button("Export gambit", new View.OnClickListener() {
            public void onClick(View v) { dlg[0].dismiss(); host.startExport(host.ed().gambitExport()); }
        }));
        box.addView(top);
        dlg[0] = new Dlg(cx).setTitle("Gambit presets").setView(box).setPositiveButton("Close", null).create();
        dlg[0].show();
        style(dlg[0]);
    }

    View view() { return scroll; }

    private boolean needSave() {
        if (host.ed() != null) return true;
        Toast.makeText(cx, "Open a save first (Save Editor tab).", Toast.LENGTH_LONG).show();
        return false;
    }

    // ---------------------------------------------------------------- small helpers

    private int dp(float v) { return Ui.dp(cx, v); }

    private TextView text(String s) {
        TextView t = new TextView(cx);
        t.setText(s);
        t.setTextColor(Ui.TEXT);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s, View.OnClickListener on) {
        Button b = new Button(cx);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Ui.CREAM);
        b.setBackground(Ui.buttonBg(cx));
        b.setOnClickListener(on);
        return b;
    }

    private void addW(LinearLayout l, View v) {
        l.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    private Spinner spinner(List<String> items) {
        Spinner sp = new Spinner(cx);
        ArrayAdapter<String> ad = Ui.adapter(cx, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        return sp;
    }

    private static String base(Map<String, Object> u) {
        String m = Editor.str(u.get("master_id"));
        return m.length() > 8 ? m.substring(0, 8) : m;
    }

    /** One roster unit per character (the highest star one), by base id. */
    private Map<String, Map<String, Object>> best() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<String, Map<String, Object>>();
        Editor ed = host.ed();
        if (ed == null) return out;
        for (Map<String, Object> u : ed.roster) {
            String b = base(u);
            Map<String, Object> cur = out.get(b);
            if (cur == null || Editor.star(Editor.str(u.get("master_id"))) > Editor.star(Editor.str(cur.get("master_id")))) out.put(b, u);
        }
        return out;
    }

    private String nameOf(String base, Map<String, Object> u) {
        if (u != null) return host.ed().name(u);
        return base;
    }

    // ---------------------------------------------------------------- list of characters

    /** Rebuilds the screen from the save (called when a save is opened). */
    void refresh() {
        Editor ed = host.ed();
        listHost.removeAllViews();
        if (presetBtn != null && ed != null) presetBtn.setText("Preset: " + ed.presetActive() + "  ▾");
        if (ed == null) {
            info.setText("No save loaded yet. Open a save first (Save Editor tab). Rules are kept inside the save.");
            return;
        }
        info.setText("");
        Map<String, Map<String, Object>> by = best();
        Map<String, Object> g = ed.gambit();
        for (String base : new ArrayList<String>(g.keySet())) listHost.addView(section(base, by.get(base)));
    }

    private View section(final String base, final Map<String, Object> unit) {
        final Editor ed = host.ed();
        final LinearLayout box = new LinearLayout(cx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(2), dp(2), dp(2), dp(2));
        android.graphics.drawable.GradientDrawable gold = new android.graphics.drawable.GradientDrawable();
        gold.setColor(0x00000000);
        gold.setStroke(Math.max(2, dp(2)), Ui.GOLD);
        gold.setCornerRadius(dp(6));
        box.setBackground(gold);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(6), 0, 0);
        box.setLayoutParams(blp);
        final List<Object> rules = ed.gambitRules(base);
        final LinearLayout head = new LinearLayout(cx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(6), dp(6), dp(6), dp(6));
        head.setBackgroundColor(Ui.PANEL);
        ImageView pic = new ImageView(cx);
        Bitmap bm = Icons.unit(base);
        if (bm != null) pic.setImageBitmap(bm);
        head.addView(pic, new LinearLayout.LayoutParams(dp(56), dp(38)));
        final TextView title = new TextView(cx);
        title.setTextColor(Ui.CREAM);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(8), 0, dp(4), 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView arrow = new TextView(cx);
        arrow.setTextColor(Ui.GOLD);
        arrow.setTextSize(18);
        head.addView(arrow);
        box.addView(head);
        final LinearLayout body = new LinearLayout(cx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.addView(body);
        final Runnable[] paint = new Runnable[1];
        paint[0] = new Runnable() {
            public void run() {
                String who = nameOf(base, unit);
                String detail = unit == null ? "  (not in this save)" : "  ★" + Editor.star(Editor.str(unit.get("master_id"))) + "  Lv " + Editor.num(unit.get("level"));
                title.setText(who + detail + "   [" + rules.size() + "]");
                boolean on = open.contains(base);
                arrow.setText(on ? " ▾" : " ▸");
                body.setVisibility(on ? View.VISIBLE : View.GONE);
            }
        };
        head.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (open.contains(base)) open.remove(base); else open.add(base);
                paint[0].run();
            }
        });
        // rows
        final LinearLayout rowsHost = new LinearLayout(cx);
        rowsHost.setOrientation(LinearLayout.VERTICAL);
        body.addView(rowsHost);
        for (Object r : rules) rowsHost.addView(ruleRow(base, rowsHost, rules, (Map<String, Object>) r, paint[0]));
        LinearLayout acts = new LinearLayout(cx);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        addW(acts, button("Add row", new View.OnClickListener() {
            public void onClick(View v) {
                if (rules.size() >= Editor.GAMBIT_MAX) { Toast.makeText(cx, "Up to " + Editor.GAMBIT_MAX + " rules per character.", Toast.LENGTH_SHORT).show(); return; }
                Map<String, Object> r = new LinkedHashMap<String, Object>();
                r.put("c", "always"); r.put("a", "attack");
                rules.add(r);
                host.changed();
                fill(base, rowsHost, rules, paint[0]);
            }
        }));
        Button rm = button("Remove character", new View.OnClickListener() {
            public void onClick(View v) {
                new Dlg(cx).setTitle("Remove all rules of this character?")
                        .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { ed.gambitRemove(base); open.remove(base); host.changed(); refresh(); }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
        rm.setBackground(Ui.redBg(cx));
        rm.setTextColor(Ui.CREAM);
        addW(acts, rm);
        body.addView(acts);
        paint[0].run();
        return box;
    }

    // ---------------------------------------------------------------- one rule row

    private static String skillLabel(Map<String, Object> r) {
        String n = Editor.str(r.get("an"));
        String id = Editor.str(r.get("a")).substring(6);
        return n.isEmpty() ? "Skill " + id : n + " (" + id + ")";
    }

    private void style(AlertDialog d) { Dlg.style(d); }

    private interface Pick { void on(int i); }

    /** Popup list: icon on the left, name (bigger) on top, description (smaller) below. */
    private void searchRich(String title, final List<String> names, final List<String> descs, final List<Bitmap> icons, final Pick cb) {
        searchRich(title, names, descs, icons, null, cb);
    }

    /** Rows whose index is in off are greyed out and cannot be picked. */
    private void searchRich(String title, final List<String> names, final List<String> descs, final List<Bitmap> icons, final Set<Integer> off, final Pick cb) {
        LinearLayout box = new LinearLayout(cx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(6), dp(12), 0);
        final EditText q = new EditText(cx);
        q.setHint("Search");
        q.setSingleLine(true);
        q.setTextColor(Ui.TEXT);
        q.setHintTextColor(Ui.DIM);
        box.addView(q);
        final ListView lv = new ListView(cx);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400)));
        final List<Integer> idx = new ArrayList<Integer>();
        final BaseAdapter ad = new BaseAdapter() {
            public int getCount() { return idx.size(); }
            public Object getItem(int p) { return idx.get(p); }
            public long getItemId(int p) { return p; }
            public boolean areAllItemsEnabled() { return off == null || off.isEmpty(); }
            public boolean isEnabled(int p) { return off == null || !off.contains(idx.get(p)); }
            public View getView(int p, View old, ViewGroup parent) {
                int i = idx.get(p);
                LinearLayout r = new LinearLayout(cx);
                if (off != null && off.contains(i)) r.setAlpha(0.35f);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(dp(4), dp(6), dp(4), dp(6));
                ImageView ic = new ImageView(cx);
                ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
                Bitmap b = icons.get(i);
                if (b != null) ic.setImageBitmap(b);
                r.addView(ic, new LinearLayout.LayoutParams(dp(44), dp(44)));
                LinearLayout col = new LinearLayout(cx);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(10), 0, 0, 0);
                TextView n = new TextView(cx);
                String nmx = names.get(i);
                int cut = nmx.indexOf('\u0001');
                if (cut >= 0) {   // "Skill (source)\u0001[Party 2]": the party name follows in a smaller, dimmer, italic font
                    android.text.SpannableString sp = new android.text.SpannableString(nmx.substring(0, cut) + "  " + nmx.substring(cut + 1));
                    int st = cut + 2;
                    sp.setSpan(new android.text.style.ForegroundColorSpan(Ui.DIM), st, sp.length(), 0);
                    sp.setSpan(new android.text.style.RelativeSizeSpan(0.8f), st, sp.length(), 0);
                    sp.setSpan(new android.text.style.StyleSpan(Typeface.ITALIC), st, sp.length(), 0);
                    n.setText(sp);
                } else n.setText(nmx);
                n.setTextSize(16);
                n.setTypeface(Typeface.DEFAULT_BOLD);
                n.setTextColor(Ui.CREAM);
                col.addView(n);
                String ds = descs.get(i);
                if (ds != null && !ds.isEmpty()) {
                    TextView d = new TextView(cx);
                    d.setText(ds);
                    d.setTextSize(12);
                    d.setTextColor(Ui.DIM);
                    col.addView(d);
                }
                r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                return r;
            }
        };
        lv.setAdapter(ad);
        final Runnable fill = new Runnable() {
            public void run() {
                String f = q.getText().toString().trim().toLowerCase(Locale.ROOT);
                idx.clear();
                for (int i = 0; i < names.size(); i++) {
                    String d = descs.get(i);
                    if (f.isEmpty() || names.get(i).toLowerCase(Locale.ROOT).contains(f) || (d != null && d.toLowerCase(Locale.ROOT).contains(f))) idx.add(i);
                }
                ad.notifyDataSetChanged();
            }
        };
        fill.run();
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { fill.run(); }
        });
        final AlertDialog d = new Dlg(cx).setTitle(title).setView(box).setNegativeButton("Cancel", null).create();
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                d.dismiss();
                cb.on(idx.get(pos));
            }
        });
        d.show();
        style(d);
    }

    /** Popup with a search box and a list; calls back with the index in the full list. */
    private void search(String title, final List<String> all, final Pick cb) {
        LinearLayout box = new LinearLayout(cx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(6), dp(12), 0);
        final EditText q = new EditText(cx);
        q.setHint("Search");
        q.setSingleLine(true);
        q.setTextColor(Ui.TEXT);
        box.addView(q);
        final ListView lv = new ListView(cx);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        final List<Integer> idx = new ArrayList<Integer>();
        final List<String> shown = new ArrayList<String>();
        final ArrayAdapter<String> ad = Ui.adapter(cx, android.R.layout.simple_list_item_1, shown);
        lv.setAdapter(ad);
        final Runnable fill = new Runnable() {
            public void run() {
                String f = q.getText().toString().trim().toLowerCase(Locale.ROOT);
                idx.clear();
                shown.clear();
                for (int i = 0; i < all.size(); i++)
                    if (f.isEmpty() || all.get(i).toLowerCase(Locale.ROOT).contains(f)) { idx.add(i); shown.add(all.get(i)); }
                ad.notifyDataSetChanged();
            }
        };
        fill.run();
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { fill.run(); }
        });
        final AlertDialog d = new Dlg(cx).setTitle(title).setView(box)
                .setNegativeButton("Cancel", null).create();
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                d.dismiss();
                cb.on(idx.get(pos));
            }
        });
        d.show();
        style(d);
    }

    /** The game's Dual cast abilities: picking one of these means "Dual cast: two spells". */
    private static final java.util.Set<String> DUAL = new java.util.HashSet<String>(java.util.Arrays.asList("1101130","1129000","1422230","1454330","200150","200160","204260","205670","206660","207740","210290","217030","223380","227640","227641","227713","228850","229637","229638","230830","231973","232792","232795","232938","233013","233014","233015","235370","239864","240088","240089","240213","240847","241544","241545","241931","241934","241936","241973","242219","243196","243470","243489","243514","243613","243617","243785","244214","244230","244701","244773","244831","244941","245545","400010","400100","400120","451061","504530","504540","506460","506500","600460","702930","702940","703210","703220","800614","800616","910142","910315","910784","910918","910919","911168","911510","911767","911776","914373","915353","915354","915355","915999","916010","917615"));
    private static boolean isDual(String act) { return act.startsWith("skill:") && DUAL.contains(act.substring(6)); }

    /** Blue magic (magic table category 4): not offered for Dual cast. */
    private static final java.util.Set<String> BLUE = new java.util.HashSet<String>(Arrays.asList("40010", "40020", "40030", "40040", "40050", "40060", "40070", "40080", "40090", "40100", "40110", "40120", "40130", "40140", "40160", "40170", "40190", "40200", "40210", "40220", "40230", "40240", "40250", "40260", "40270", "40280", "40290", "40300", "40310", "40320", "40330", "40340", "40350", "40360", "40370", "40380", "40390", "40400", "40410", "40420", "40430", "40440", "40450", "40460", "40470", "40480", "40490", "40500", "70547", "70548"));

    /** Magic spells this unit has learned (own skills, materia, esper boards), no blue magic: {id, name}. */
    private List<String[]> dualSpells(String base) {
        Map<String, Object> unit = best().get(base);
        List<String> mats = new ArrayList<String>();
        if (unit != null && unit.get("materia") instanceof List) for (Object o : (List<Object>) unit.get("materia")) mats.add(Editor.str(o));
        int star = unit == null ? 7 : Editor.star(Editor.str(unit.get("master_id")));
        java.util.LinkedHashMap<String, String> got = new java.util.LinkedHashMap<String, String>();
        for (String[] k : SkillNames.forUnit(base, star, new ArrayList<String>())) got.put(k[0], k[1]);
        Editor eds = host.ed();
        for (SkillNames.Party pt : SkillNames.partiesOf(eds == null ? null : eds.save, base, mats)) {
            for (String[] k : SkillNames.materiaSkills(pt.materia)) if (!got.containsKey(k[0])) got.put(k[0], k[1]);
            java.util.Set<String> one = null;
            if (pt.esper != null) { one = new java.util.HashSet<String>(); one.add(pt.esper); }
            if (pt.esper != null || true) for (String[] k : SkillNames.esperSkills(eds == null ? null : eds.save, one))
                if (k[0].startsWith("skill:") && !got.containsKey(k[2])) got.put(k[2], k[1]);
        }
        List<String[]> out = new ArrayList<String[]>();
        for (Map.Entry<String, String> e : got.entrySet()) {
            String id = e.getKey();
            if (id.length() != 5 || BLUE.contains(id)) continue;     // magic only: 5-digit ids are magic, 6-digit are abilities
            out.add(new String[]{id, e.getValue().replace(" (materia)", "")});
        }
        return out;
    }

    /** The two spell choosers under the Do line (shown only for a Dual cast action). */
    private LinearLayout dualRow(final String base, final Map<String, Object> rule, final Runnable after) {
        final LinearLayout box = new LinearLayout(cx);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int slot = 0; slot < 2; slot++) {
            final int sl = slot;
            LinearLayout l = new LinearLayout(cx);
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.addView(new View(cx), new LinearLayout.LayoutParams(dp(56), 1));
            final Button b = button(dualLabel(rule, sl) + "  \u25be", null);
            l.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    SkillNames.init(cx);
                    final List<String[]> sp = dualSpells(base);
                    if (sp.isEmpty()) { Toast.makeText(cx, "No learned magic spells found for this unit (load the skill names first).", Toast.LENGTH_LONG).show(); return; }
                    List<String> names = new ArrayList<String>(), descs = new ArrayList<String>();
                    List<Bitmap> icons = new ArrayList<Bitmap>();
                    for (String[] k : sp) {
                        names.add(k[1]);
                        String ds = SkillNames.mDesc.get(k[0]);
                        descs.add(ds == null ? "" : ds);
                        icons.add(SkillNames.icon(k[0]));
                    }
                    searchRich(sl == 0 ? "Dual cast: first spell" : "Dual cast: second spell", names, descs, icons, new Pick() {
                        public void on(int i) {
                            String[] cur = dualParts(rule);
                            cur[sl] = sp.get(i)[0];
                            cur[2 + sl] = sp.get(i)[1];
                            rule.put("a", "dual:" + cur[0] + ":" + cur[1]);
                            rule.put("an", (cur[2].isEmpty() ? "?" : cur[2]) + " + " + (cur[3].isEmpty() ? "?" : cur[3]));
                            b.setText(dualLabel(rule, sl) + "  \u25be");
                            host.changed();
                            after.run();
                        }
                    });
                }
            });
            box.addView(l);
        }
        return box;
    }

    /** {idA, idB, nameA, nameB} of a dual:A:B action ("0" = not chosen yet). */
    private static String[] dualParts(Map<String, Object> rule) {
        String a = Editor.str(rule.get("a")), an = Editor.str(rule.get("an"));
        String[] p = a.startsWith("dual:") ? a.substring(5).split(":") : new String[0];
        String[] n = an.split(" \\+ ", 2);
        String[] r = new String[]{"0", "0", "", ""};
        for (int i = 0; i < 2 && i < p.length; i++) r[i] = p[i];
        for (int i = 0; i < 2 && i < n.length; i++) r[2 + i] = n[i].equals("?") ? "" : n[i];
        return r;
    }

    private static String dualLabel(Map<String, Object> rule, int slot) {
        String[] p = dualParts(rule);
        String n = p[2 + slot];
        if (n.isEmpty() && !p[slot].equals("0")) n = "spell " + p[slot];
        return "Spell " + (slot + 1) + ": " + (n.isEmpty() ? "Choose" : n);
    }

    private static String actionText(Map<String, Object> r) {
        String a = Editor.str(r.get("a"));
        if (a.equals("defend")) return "Defend";
        if (a.equals("lb")) return "Limit Burst";
        if (a.equals("summon")) return "Cast summon";
        if (a.startsWith("esper:")) { String n = Editor.str(r.get("an")); return "Cast: " + (n.isEmpty() ? "esper " + a.substring(6) : n) + " (esper)"; }
        if (a.startsWith("skill:")) return "Cast: " + skillLabel(r);
        if (a.startsWith("dual:")) { String n = Editor.str(r.get("an")); return "Dual cast: " + (n.isEmpty() ? a.substring(5) : n); }
        if (a.startsWith("item:")) { String n = Editor.str(r.get("an")); return "Use item: " + (n.isEmpty() ? a.substring(5) : n + " (" + a.substring(5) + ")"); }
        return "Attack";
    }

    /** Short plain text of one rule for the on-screen notice, e.g. "Ally: KO'd count >= 1, cast Raise". ASCII only. */
    static String ruleText(Map<String, Object> r) {
        StringBuilder sb = new StringBuilder();
        java.util.List<Object> conds = new ArrayList<Object>();
        conds.add(r);
        Object more = r.get("and");
        if (more instanceof java.util.List) conds.addAll((java.util.List<Object>) more);
        for (Object o : conds) {
            if (!(o instanceof Map)) continue;
            if (sb.length() > 0) sb.append(" & ");
            sb.append(condLine((Map<String, Object>) o));
        }
        sb.append(", ").append(actionShort(r));
        return sb.toString();
    }

    private static String cap(String t) {
        return t.isEmpty() ? "" : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    static String condLine(Map<String, Object> c) {
        String id = Editor.str(c.get("c")), k = kind(id), t = cap(Editor.str(c.get("t"))), op = Editor.str(c.get("op")), n = Editor.str(c.get("n")), v = Editor.str(c.get("v"));
        String label = condText(id);
        String unit = id.endsWith("_pct") || id.equals("lb") ? "%" : "";
        if (id.equals("always")) return "Always";
        if (k.equals("status")) return (t.isEmpty() ? "Self" : t) + (op.equals("isnot") ? " lacks " : " has ") + (v.isEmpty() ? "status" : v);
        if (k.equals("count")) return label + " " + (op.isEmpty() ? ">=" : op) + " " + n + unit;
        if (k.equals("val2") || k.equals("val3")) return (t.isEmpty() ? "Self" : t) + " " + label + " " + op + " " + n + unit;
        if (k.equals("elem") || k.equals("sres")) return label + " " + (v.isEmpty() ? "" : v + " ") + op + " " + n + "%";
        if (k.equals("race")) return label + " " + v;
        if (id.equals("turn_mod")) return "Turn " + Editor.str(c.get("o")) + " of " + v;   // o = which turn, v = rotation length (the rule runs on turn o, o+v, o+2v...)
        return label;
    }

    private static String actionShort(Map<String, Object> r) {
        String a = Editor.str(r.get("a")), an = Editor.str(r.get("an"));
        if (a.equals("defend")) return "defend";
        if (a.equals("lb")) return "Limit Burst";
        if (a.equals("summon")) return "summon";
        if (a.startsWith("esper:")) return "cast " + (an.isEmpty() ? "esper " + a.substring(6) : an);
        if (a.startsWith("skill:")) return "cast " + (an.isEmpty() ? "skill " + a.substring(6) : an);
        if (a.startsWith("dual:")) return "dual cast " + (an.isEmpty() ? a.substring(5) : an);
        if (a.startsWith("item:")) return "use " + (an.isEmpty() ? a.substring(5) : an);
        return "attack";
    }

    private static String statusText(Map<String, Object> r) {
        String v = Editor.str(r.get("v"));
        return v.isEmpty() ? "Choose status" : v;
    }

    private static String condText(String id) {
        for (String[] c : CONDS) if (c[0].equals(id)) return c[1];
        return CONDS.get(0)[1];
    }

    private void askId(String title, String hint, final Pick2 cb) {
        final EditText in = new EditText(cx);
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        in.setHint(hint);
        new Dlg(cx).setTitle(title).setView(in)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String t = in.getText().toString().trim();
                        if (!t.isEmpty()) cb.on(t);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private EditText numField(String hint, String val, final Map<String, Object> rule, final String key) {
        EditText e = new EditText(cx);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setGravity(Gravity.CENTER);
        e.setTextColor(Ui.TEXT);
        e.setText(val);
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                String t = s.toString().trim();
                if (t.isEmpty()) { if (rule.remove(key) != null) host.changed(); }
                else if (!t.equals(Editor.str(rule.get(key)))) { rule.put(key, t); host.changed(); }
            }
        });
        return e;
    }

    private interface Pick2 { void on(String s); }

    /** Small gold-bordered square button, same design and size as the gear and filter buttons. */
    /** Trash can button: right edge lines up with the right edge of the dropdown buttons above. */
    private LinearLayout.LayoutParams trashLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(26));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(2);
        return lp;
    }

    private static int ident(List<Object> l, Object o) {
        for (int i = 0; i < l.size(); i++) if (l.get(i) == o) return i;
        return -1;
    }

    private static void removeIdent(List<Object> l, Object o) {
        int i = ident(l, o);
        if (i >= 0) l.remove(i);
    }

    private View miniBtn(View icon, View.OnClickListener on) { return miniBtn(icon, on, Ui.NAVY); }

    private View miniBtn(View icon, View.OnClickListener on, int fill) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(cx);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(6));
        g.setStroke(Math.max(1, dp(1)), Ui.GOLD);
        f.setBackground(g);
        f.setOnClickListener(on);
        f.addView(icon, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return f;
    }

    /** The same round gold "?" as in the Save Editor and Patch tabs. */
    private View roundHelp(View.OnClickListener on) {
        TextView q = new TextView(cx);
        q.setText("?");
        q.setTextSize(14);
        q.setTypeface(Typeface.DEFAULT_BOLD);
        q.setGravity(Gravity.CENTER);
        q.setTextColor(Ui.GOLD);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(Ui.NAVY);
        g.setStroke(Math.max(1, dp(1)), Ui.GOLD);
        q.setBackground(g);
        q.setOnClickListener(on);
        return q;
    }

    private LinearLayout.LayoutParams roundLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(2);
        return lp;
    }

    private LinearLayout.LayoutParams miniLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(26));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(2);
        return lp;
    }

    private View miniText(String t, float size) {
        TextView v = new TextView(cx);
        v.setText(t);
        v.setTextSize(size);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(Ui.GOLD);
        v.setIncludeFontPadding(false);
        return v;
    }

    private View miniIcon(Drawable d) {
        ImageView iv = new ImageView(cx);
        iv.setImageDrawable(d);
        return iv;
    }

    static final int MAX_CONDS = 4;

    /** One condition: "When"/"And" button plus the extra fields it needs. The condition data lives in the map. */
    private View condBlock(final Map<String, Object> cond, boolean extra, final Runnable onRemove) {
        migrate(cond);
        final LinearLayout box = new LinearLayout(cx);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout l1 = new LinearLayout(cx);
        l1.setOrientation(LinearLayout.HORIZONTAL);
        l1.setGravity(Gravity.CENTER_VERTICAL);
        TextView lab = text(extra ? "And" : "When");
        lab.setTextColor(Ui.GOLD);
        l1.addView(lab, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        final Button bc = button(condText(Editor.str(cond.get("c"))) + "  ▾", null);
        l1.addView(bc, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (extra) l1.addView(miniBtn(miniIcon(Ui.trash(cx, 14)), new View.OnClickListener() {
            public void onClick(View v) { onRemove.run(); }
        }, 0xff8b2a2a), trashLp());
        box.addView(l1);
        final LinearLayout l3 = new LinearLayout(cx);
        l3.setOrientation(LinearLayout.HORIZONTAL);
        l3.setGravity(Gravity.CENTER_VERTICAL);
        final EditText fy = numField("y", Editor.str(cond.get("o")), cond, "o");
        final EditText fx = numField("x", Editor.str(cond.get("v")), cond, "v");
        View pad = new View(cx);
        l3.addView(pad, new LinearLayout.LayoutParams(dp(56), 1));
        l3.addView(text("Turn "));
        l3.addView(fy, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
        l3.addView(text(" of a "));
        l3.addView(fx, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
        l3.addView(text("-turn rotation."));
        TextView q = new TextView(cx);
        q.setText("?");
        q.setTextSize(16);
        q.setTypeface(Typeface.DEFAULT_BOLD);
        q.setTextColor(Ui.GOLD);
        q.setGravity(Gravity.CENTER);
        q.setPadding(dp(8), dp(4), dp(8), dp(4));
        q.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new Dlg(cx).setTitle("Turn modulo")
                        .setMessage("Turn <y> of a <x>-turn rotation.\n\nExamples:\nTurn 1 of a 2-turn rotation = turns 1, 3, 5, 7...\nTurn 2 of a 2-turn rotation = turns 2, 4, 6, 8...\nTurn 3 of a 3-turn rotation = turns 3, 6, 9...\nTurn 1 of a 4-turn rotation = turns 1, 5, 9...")
                        .setPositiveButton("OK", null).show();
            }
        });
        l3.addView(q);
        for (int z = 0; z < l3.getChildCount(); z++) {
            View ch = l3.getChildAt(z);
            if (ch instanceof TextView && !(ch instanceof EditText) && ch != q) ((TextView) ch).setTextSize(13);
        }
        box.addView(l3);
        final LinearLayout pb = new LinearLayout(cx);
        pb.setOrientation(LinearLayout.VERTICAL);
        box.addView(pb);
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            public void run() {
                pb.removeAllViews();
                String id = Editor.str(cond.get("c"));
                String k = kind(id);
                l3.setVisibility(k.equals("turn") ? View.VISIBLE : View.GONE);
                if (k.equals("none") || k.equals("turn")) return;
                // line 1: target and status / element / race
                if (k.equals("status") || k.equals("val2") || k.equals("val3") || k.equals("elem") || k.equals("sres")) {
                    final String[] tl = k.equals("val2") ? new String[]{"Self", "Ally"} : new String[]{"Self", "Ally", "Foe"};
                    pb.addView(paramRow("Target", tl, "t", cond, rebuild[0]));
                }
                if (k.equals("status")) {
                    pb.addView(paramRow("Is", new String[]{"Status is", "Status is not"}, "op", cond, null, new String[]{"is", "isnot"}));
                    pb.addView(valueRow("Status", Arrays.asList(STATUSES), cond, "Choose status"));
                } else if (k.equals("elem")) {
                    pb.addView(valueRow("Element", Arrays.asList(ELEMENTS), cond, "All elements"));
                } else if (k.equals("sres")) {
                    pb.addView(valueRow("Status", Arrays.asList(RES_STATUS), cond, "All status resistances"));
                } else if (k.equals("race")) {
                    pb.addView(valueRow("Race", Arrays.asList(RACES), cond, "Choose race"));
                }
                if (k.equals("count") || k.equals("val2") || k.equals("val3") || k.equals("elem") || k.equals("sres")) {
                    String unit = id.endsWith("_pct") || id.equals("lb") || k.equals("elem") || k.equals("sres") ? "%" : "";
                    String range = k.equals("elem") ? "-100 to 100" : k.equals("sres") ? "0 to 100" : "";
                    pb.addView(opRow(cond, unit, range));
                }
            }
        };
        rebuild[0].run();
        bc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                List<String> labels = new ArrayList<String>(), cd = new ArrayList<String>();
                List<Bitmap> ci = new ArrayList<Bitmap>();
                Set<Integer> coff = new HashSet<Integer>();
                for (String[] c : CONDS) {
                    if (offCond(c[0])) coff.add(labels.size());
                    labels.add(c[1]);
                    cd.add(offCond(c[0]) ? "Not working yet. Turned off for now" : "");
                    ci.add(null);
                }
                searchRich("Condition", labels, cd, ci, coff, new Pick() {
                    public void on(int i) {
                        String id = CONDS.get(i)[0];
                        cond.put("c", id);
                        for (String k : new String[]{"o", "v", "t", "op", "n"}) cond.remove(k);
                        String kd = kind(id);
                        if (kd.equals("status")) { cond.put("t", "self"); cond.put("op", "is"); }
                        else if (kd.equals("count")) cond.put("op", ">=");
                        else if (kd.equals("val2") || kd.equals("val3")) { cond.put("t", "self"); cond.put("op", "<"); }
                        else if (kd.equals("elem") || kd.equals("sres")) { cond.put("t", "foe"); cond.put("op", "<"); cond.put("v", kd.equals("elem") ? ELEMENTS[0] : RES_STATUS[0]); }
                        fx.setText("");
                        fy.setText("");
                        bc.setText(CONDS.get(i)[1] + "  ▾");
                        rebuild[0].run();
                        host.changed();
                    }
                });
            }
        });
        return box;
    }

    private static String labelOf(String[] vals, String[] keys, String key) {
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(key)) return vals[i];
        return vals[0];
    }

    /** A row "label [dropdown]" choosing one of a short list; stores the list entry (or keys[i]) under map[key]. */
    private View paramRow(String label, final String[] vals, final String key, final Map<String, Object> cond, final Runnable after) {
        String[] keys = new String[vals.length];
        for (int i = 0; i < vals.length; i++) keys[i] = vals[i].toLowerCase();
        return paramRow(label, vals, key, cond, after, keys);
    }

    private View paramRow(String label, final String[] vals, final String key, final Map<String, Object> cond, final Runnable after, final String[] keys) {
        LinearLayout l = new LinearLayout(cx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(label);
        l.addView(t, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        final Button b = button(labelOf(vals, keys, Editor.str(cond.get(key))) + "  ▾", null);
        l.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                search(null == null ? "Choose" : "", Arrays.asList(vals), new Pick() {
                    public void on(int i) {
                        cond.put(key, keys[i]);
                        b.setText(vals[i] + "  ▾");
                        host.changed();
                        if (after != null) after.run();
                    }
                });
            }
        });
        return l;
    }

    /** A row "label [dropdown]" choosing from a long searchable list; stores the chosen text under "v". */
    private View valueRow(String label, final List<String> all, final Map<String, Object> cond, String empty) {
        LinearLayout l = new LinearLayout(cx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.addView(text(label), new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        String cur = Editor.str(cond.get("v"));
        final Button b = button((cur.isEmpty() ? empty : cur) + "  ▾", null);
        l.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (all.size() == STATUSES.length && all.get(0).equals(STATUSES[0])) {   // status list: show the group under each name
                    List<String> ds = new ArrayList<String>();
                    List<Bitmap> ci = new ArrayList<Bitmap>();
                    for (int k = 0; k < all.size(); k++) { ds.add(k < AILMENTS.length ? "Ailment" : k < AILMENTS.length + BUFFS.length ? "Buff" : "Debuff"); ci.add(null); }
                    searchRich("Choose status", all, ds, ci, new Pick() {
                        public void on(int i) { cond.put("v", all.get(i)); b.setText(all.get(i) + "  ▾"); host.changed(); }
                    });
                    return;
                }
                search(label0(all), all, new Pick() {
                    public void on(int i) { cond.put("v", all.get(i)); b.setText(all.get(i) + "  ▾"); host.changed(); }
                });
            }
        });
        return l;
    }

    private static String label0(List<String> all) { return "Choose"; }

    /** Row: [operator dropdown] [number] unit (range). */
    private View opRow(final Map<String, Object> cond, String unit, String range) {
        LinearLayout l = new LinearLayout(cx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.addView(new View(cx), new LinearLayout.LayoutParams(dp(56), 1));
        String op = Editor.str(cond.get("op"));
        if (op.isEmpty() || op.equals("is") || op.equals("isnot")) op = "<";
        final Button b = button(op + "  ▾", null);
        l.addView(b, new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                search("Compare", Arrays.asList(OPS), new Pick() {
                    public void on(int i) { cond.put("op", OPS[i]); b.setText(OPS[i] + "  ▾"); host.changed(); }
                });
            }
        });
        if (Editor.str(cond.get("op")).isEmpty()) cond.put("op", op);
        EditText n = numField("number", Editor.str(cond.get("n")), cond, "n");
        l.addView(n, new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView u = text(unit + (range.isEmpty() ? "" : "  (" + range + ")"));
        u.setTextSize(13);
        l.addView(u);
        return l;
    }

    /** Disabled rule: every dropdown and number box turns flat grey without a border. */
    private void greyOut(View v) {
        if (v instanceof Button) {
            Button b = (Button) v;
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(0xff4a4d57);
            g.setCornerRadius(dp(6));
            b.setBackground(g);
            b.setTextColor(0xffa0a3ad);
        } else if (v instanceof EditText) {
            ((EditText) v).setTextColor(0xffa0a3ad);
            ((EditText) v).setBackgroundColor(0x00000000);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) greyOut(g.getChildAt(i));
        }
    }

    private View ruleRow(final String base, final LinearLayout rowsHost, final List<Object> rules, final Map<String, Object> rule, final Runnable repaint) {
        final LinearLayout row = new LinearLayout(cx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(6), dp(6), dp(6), dp(8));
        row.setBackgroundColor(rule.get("off") != null ? 0x44808080 : 0x22d4b46a);
        row.setTag(rule);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.setMargins(0, dp(6), 0, 0);
        row.setLayoutParams(gap);
        // top: position number on the left, trash can on the right
        LinearLayout l1 = new LinearLayout(cx);
        l1.setOrientation(LinearLayout.HORIZONTAL);
        l1.setGravity(Gravity.CENTER_VERTICAL);
        l1.addView(miniBtn(miniText((ident(rules, rule) + 1) + "▾", 12), new View.OnClickListener() {
            public void onClick(View v) {
                final List<String> nums = new ArrayList<String>();
                for (int k = 1; k <= rules.size(); k++) nums.add(String.valueOf(k));
                new Dlg(cx).setTitle("Move to position")
                        .setItems(nums.toArray(new String[0]), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (w == ident(rules, rule)) return;
                                removeIdent(rules, rule);
                                rules.add(w, rule);
                                host.changed();
                                fill(base, rowsHost, rules, repaint);
                            }
                        }).show();
            }
        }), new LinearLayout.LayoutParams(dp(36), dp(26)));
        l1.addView(new View(cx), new LinearLayout.LayoutParams(0, 1, 1));
        // disable / enable: a disabled rule is skipped by Gambit and drawn grey. The icon shows what a tap does.
        final boolean off = rule.get("off") != null;
        l1.addView(miniBtn(miniIcon(off ? Ui.enableIcon(cx, 14) : Ui.disableIcon(cx, 14)), new View.OnClickListener() {
            public void onClick(View v) {
                if (rule.get("off") != null) rule.remove("off"); else rule.put("off", Boolean.TRUE);
                host.changed();
                fill(base, rowsHost, rules, repaint);
            }
        }, off ? 0xff2e7d32 : Ui.NAVY), trashLp());
        l1.addView(miniBtn(miniIcon(Ui.trash(cx, 14)), new View.OnClickListener() {
            public void onClick(View v) {
                removeIdent(rules, rule);
                host.changed();
                fill(base, rowsHost, rules, repaint);
            }
        }, 0xff8b2a2a), trashLp());
        row.addView(l1);
        // conditions (joined with AND), then the + button
        final List<Object> more = (List<Object>) (rule.get("and") instanceof List ? rule.get("and") : new ArrayList<Object>());
        if (!more.isEmpty()) rule.put("and", more);
        row.addView(condBlock(rule, false, null));
        for (final Object o : new ArrayList<Object>(more)) {
            row.addView(condBlock((Map<String, Object>) o, true, new Runnable() {
                public void run() {
                    removeIdent(more, o);
                    if (more.isEmpty()) rule.remove("and");
                    host.changed();
                    fill(base, rowsHost, rules, repaint);
                }
            }));
        }
        if (more.size() + 1 < MAX_CONDS) {
            LinearLayout pl = new LinearLayout(cx);
            pl.setGravity(Gravity.CENTER);
            pl.setPadding(0, dp(6), 0, dp(2));
            pl.addView(miniBtn(miniText("+", 16), new View.OnClickListener() {
                public void onClick(View v) {
                    Map<String, Object> c = new LinkedHashMap<String, Object>();
                    c.put("c", "always");
                    more.add(c);
                    rule.put("and", more);
                    host.changed();
                    fill(base, rowsHost, rules, repaint);
                }
            }), new LinearLayout.LayoutParams(dp(36), dp(26)));
            row.addView(pl);
        }
        // action
        LinearLayout l2 = new LinearLayout(cx);
        l2.setOrientation(LinearLayout.HORIZONTAL);
        l2.setGravity(Gravity.CENTER_VERTICAL);
        TextView dl = text("Do");
        dl.setTextColor(Ui.GOLD);
        l2.addView(dl, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        final Button ba = button(actionText(rule) + "  ▾", null);
        l2.addView(ba, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(l2);
        final LinearLayout dualBox = new LinearLayout(cx);
        dualBox.setOrientation(LinearLayout.VERTICAL);
        if (Editor.str(rule.get("a")).startsWith("dual:")) dualBox.addView(dualRow(base, rule, new Runnable() { public void run() { ba.setText(actionText(rule) + "  \u25be"); } }));
        else dualBox.setVisibility(View.GONE);
        row.addView(dualBox);
        ba.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                SkillNames.init(cx);
                final List<String> labels = new ArrayList<String>(), descs = new ArrayList<String>();
                final List<Bitmap> icons = new ArrayList<Bitmap>();
                final List<String[]> acts = new ArrayList<String[]>();    // {action, name}
                labels.add("Attack"); descs.add("Normal attack"); icons.add(gearIcon("Weapon")); acts.add(new String[]{"attack", ""});
                labels.add("Defend"); descs.add("Guard to take less damage"); icons.add(gearIcon("Shield")); acts.add(new String[]{"defend", ""});
                labels.add("Limit Burst"); descs.add("Use the Limit Burst when its gauge is full"); icons.add(lbIcon()); acts.add(new String[]{"lb", ""});
                labels.add("Cast summon"); descs.add("Call the equipped esper when the summon gauge is full"); icons.add(asset("summon_icon.png")); acts.add(new String[]{"summon", ""});
                final boolean[] hasDual = {false};
                Map<String, Object> unit = best().get(base);
                List<String> mats = new ArrayList<String>();
                if (unit != null && unit.get("materia") instanceof List) for (Object o : (List<Object>) unit.get("materia")) mats.add(Editor.str(o));
                int star = unit == null ? 7 : Editor.star(Editor.str(unit.get("master_id")));
                // the unit's own skills (no materia): plain
                for (String[] k : SkillNames.forUnit(base, star, new ArrayList<String>())) {
                    if (DUAL.contains(k[0])) { hasDual[0] = true; continue; }
                    labels.add("Cast: " + k[1]);
                    String ds = SkillNames.aDesc.get(k[0]);
                    if (ds == null) ds = SkillNames.mDesc.get(k[0]);
                    descs.add(ds == null ? "" : ds);
                    icons.add(SkillNames.icon(k[0]));
                    acts.add(new String[]{"skill:" + k[0], k[1]});
                }
                // materia and esper skills come per saved party (a unit can wear other materia or carry another esper in each party): the party name is shown after the skill in a dimmer, smaller font
                Editor eds = host.ed();
                List<SkillNames.Party> parties = SkillNames.partiesOf(eds == null ? null : eds.save, base, mats);
                boolean multi = parties.size() > 1;
                java.util.LinkedHashMap<String, String[]> extra = new java.util.LinkedHashMap<String, String[]>();   // action -> {action, name, id, tags}
                for (SkillNames.Party pt : parties) {
                    List<String[]> got = new ArrayList<String[]>();
                    for (String[] k : SkillNames.materiaSkills(pt.materia)) got.add(new String[]{"skill:" + k[0], k[1], k[0], "m"});
                    java.util.Set<String> one = null;
                    if (pt.esper != null) { one = new java.util.HashSet<String>(); one.add(pt.esper); }
                    if (pt.esper != null || parties.size() == 1) for (String[] k : SkillNames.esperSkills(eds == null ? null : eds.save, one)) got.add(new String[]{k[0], k[1], k[2], k[0].startsWith("esper:") ? "s" : "k", k[3]});
                    for (String[] k : got) {
                        String[] cur = extra.get(k[0]);
                        if (cur == null) { cur = new String[]{k[0], k[1], k[2], "", k[3].equals("m") ? k[1].replace(" (materia)", "") : (k.length > 4 ? k[4] : k[1])}; extra.put(k[0], cur); }
                        if (multi && !pt.name.isEmpty() && !cur[3].contains(pt.name)) cur[3] = cur[3].isEmpty() ? pt.name : cur[3] + ", " + pt.name;
                    }
                }
                for (String[] k : extra.values()) {
                    if (isDual(k[0])) { hasDual[0] = true; continue; }
                    boolean isE = k[0].startsWith("esper:");
                    String tag = k[3].isEmpty() ? "" : "[" + k[3] + "]";
                    labels.add("Cast: " + k[1] + (tag.isEmpty() ? "" : "\u0001" + tag));
                    String ds = isE ? SkillNames.eDesc.get(k[2]) : SkillNames.aDesc.get(k[2]);
                    if (ds == null && !isE) ds = SkillNames.mDesc.get(k[2]);
                    descs.add(ds == null ? "" : ds);
                    icons.add(isE ? asset("summon_icon.png") : SkillNames.icon(k[2]));
                    String an = isE ? k[4] : (k[1].endsWith(" (materia)") ? k[1] : k[1]);
                    acts.add(new String[]{k[0], an + (tag.isEmpty() ? "" : " " + tag)});
                }
                // Dual cast sits right below Limit Burst, and only for a unit that has a Dual cast ability
                int dualAt = -1;
                if (hasDual[0] || Editor.str(rule.get("a")).startsWith("dual:")) {
                    dualAt = 2;   // Defend, Limit Burst, then this
                    for (int q = 0; q < labels.size(); q++) if (labels.get(q).equals("Limit Burst")) { dualAt = q + 1; break; }
                    labels.add(dualAt, "Dual cast: two spells"); descs.add(dualAt, "Uses Dual cast, then casts the 2 spells you pick (magic the unit learned, no blue magic). Not tested yet"); icons.add(dualAt, asset("dual_icon.png"));
                    acts.add(dualAt, new String[]{"dual:0:0", ""});
                }
                final int dualPos = dualAt;
                final int itemAt = labels.size();
                labels.add("Use item"); descs.add("Not working yet. Turned off for now"); icons.add(null);
                final Set<Integer> offRows = new HashSet<Integer>();
                offRows.add(itemAt);
                if (!SkillNames.hasNames()) { labels.add("Load skill and item names from the game..."); descs.add("Needs the game running"); icons.add(null); }
                searchRich("Action", labels, descs, icons, offRows, new Pick() {
                    public void on(int i0) {
                        int i = i0;
                        if (i == dualPos) {
                            rule.put("a", "dual:0:0");
                            rule.put("an", "? + ?");
                            ba.setText(actionText(rule) + "  \u25be");
                            host.changed();
                            dualBox.setVisibility(View.VISIBLE);
                            dualBox.removeAllViews();
                            dualBox.addView(dualRow(base, rule, new Runnable() { public void run() { ba.setText(actionText(rule) + "  \u25be"); } }));
                            return;
                        } else if (i < itemAt) {
                            dualBox.setVisibility(View.GONE);
                            rule.put("a", acts.get(i)[0]);
                            if (acts.get(i)[1].isEmpty()) rule.remove("an"); else rule.put("an", acts.get(i)[1]);
                        } else if (i == itemAt) {
                            pickItem(rule, ba);
                            return;
                        } else {
                            Toast.makeText(cx, "Reading skill names from the game...", Toast.LENGTH_SHORT).show();
                            new Thread(new Runnable() {
                                public void run() {
                                    String m;
                                    try { SkillNames.fetch(cx); m = "Skill names loaded. Open the Action list again."; }
                                    catch (Exception e) { m = String.valueOf(e.getMessage()); }
                                    final String mm = m;
                                    ba.post(new Runnable() { public void run() { Toast.makeText(cx, mm, Toast.LENGTH_LONG).show(); } });
                                }
                            }).start();
                            return;
                        }
                        ba.setText(actionText(rule) + "  ▾");
                        host.changed();
                    }
                });
            }
        });
        if (rule.get("off") != null) greyOut(row);
        return row;
    }

    private void fill(String base, LinearLayout rowsHost, List<Object> rules, Runnable repaint) {
        rowsHost.removeAllViews();
        for (Object r : rules) rowsHost.addView(ruleRow(base, rowsHost, rules, (Map<String, Object>) r, repaint));
        repaint.run();
    }

    private android.content.SharedPreferences prefs() { return cx.getSharedPreferences("editor", Context.MODE_PRIVATE); }

    private void pickCharacter() {
        final Editor ed = host.ed();
        final List<Map<String, Object>> all = new ArrayList<Map<String, Object>>(best().values());
        final List<Map<String, Object>> shown = new ArrayList<Map<String, Object>>();
        LinearLayout box = new LinearLayout(cx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(6), dp(12), 0);
        final EditText q = new EditText(cx);
        q.setHint("Search characters");
        q.setSingleLine(true);
        q.setTextColor(Ui.TEXT);
        q.setHintTextColor(Ui.DIM);
        box.addView(q);
        final ListView lv = new ListView(cx);
        final BaseAdapter ad = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View old, ViewGroup parent) {
                LinearLayout r = new LinearLayout(cx);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(dp(8), dp(4), dp(8), dp(4));
                Map<String, Object> u = shown.get(i);
                ImageView pic = new ImageView(cx);
                Bitmap bm = Icons.unit(base(u));
                if (bm != null) pic.setImageBitmap(bm);
                r.addView(pic, new LinearLayout.LayoutParams(dp(56), dp(38)));
                TextView t = new TextView(cx);
                t.setTextColor(Ui.TEXT);
                t.setText(ed.name(u) + "\n\u2605" + Editor.star(Editor.str(u.get("master_id"))) + "   Lv " + Editor.num(u.get("level")));
                t.setPadding(dp(10), 0, 0, 0);
                r.addView(t);
                return r;
            }
        };
        lv.setAdapter(ad);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        final Runnable apply = new Runnable() {
            public void run() {
                final String sort = prefs().getString("gSort", "star");
                String sf = prefs().getString("gStars", "");
                Set<String> stars = new HashSet<String>();
                if (!sf.isEmpty()) for (String x : sf.split(",")) stars.add(x);
                String f = q.getText().toString().trim().toLowerCase(Locale.ROOT);
                shown.clear();
                for (Map<String, Object> u : all) {
                    if (!stars.isEmpty() && !stars.contains(String.valueOf(Editor.star(Editor.str(u.get("master_id")))))) continue;
                    if (!f.isEmpty() && !ed.name(u).toLowerCase(Locale.ROOT).contains(f)) continue;
                    shown.add(u);
                }
                Collections.sort(shown, new Comparator<Map<String, Object>>() {
                    public int compare(Map<String, Object> a, Map<String, Object> b) {
                        int sa = Editor.star(Editor.str(a.get("master_id"))), sb = Editor.star(Editor.str(b.get("master_id")));
                        long la = Editor.num(a.get("level")), lb = Editor.num(b.get("level"));
                        int byStar = sb - sa, byLevel = la == lb ? 0 : (lb > la ? 1 : -1);
                        int byName = ed.name(a).compareToIgnoreCase(ed.name(b));
                        if (sort.equals("name")) return byName != 0 ? byName : byStar != 0 ? byStar : byLevel;
                        if (sort.equals("level")) return byLevel != 0 ? byLevel : byStar != 0 ? byStar : byName;
                        return byStar != 0 ? byStar : byLevel != 0 ? byLevel : byName;
                    }
                });
                ad.notifyDataSetChanged();
            }
        };
        apply.run();
        // title row: title on the left, sort and filter button on the right
        LinearLayout tr = new LinearLayout(cx);
        tr.setOrientation(LinearLayout.HORIZONTAL);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        tr.setPadding(dp(20), dp(14), dp(16), dp(4));
        TextView tt = new TextView(cx);
        tt.setText("Add character");
        tt.setTextSize(18);
        tt.setTypeface(Typeface.SERIF, Typeface.BOLD);
        tt.setTextColor(Ui.GOLD);
        tr.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tr.addView(miniBtn(miniIcon(Ui.funnel(cx, 14)), new View.OnClickListener() {
            public void onClick(View v) { sortDialog(apply); }
        }), miniLp());
        final AlertDialog dlg = new Dlg(cx).setCustomTitle(tr).setView(box)
                .setNegativeButton("Cancel", null).create();
        q.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { apply.run(); }
        });
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                String b = base(shown.get(pos));
                if (!ed.gambit().containsKey(b)) { ed.gambitRules(b); host.changed(); }
                open.add(b);
                dlg.dismiss();
                refresh();
            }
        });
        dlg.show();
        style(dlg);
    }

    /** Sort by (name, star, level) and star filter; remembered between runs. */
    private void sortDialog(final Runnable apply) {
        LinearLayout box = new LinearLayout(cx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView h = new TextView(cx);
        h.setText("Sort by");
        h.setTextColor(Ui.GOLD);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(h);
        final android.widget.RadioGroup rg = new android.widget.RadioGroup(cx);
        final String[] keys = {"star", "level", "name"}, labels = {"Star (then level, name)", "Level (then star, name)", "Name"};
        String cur = prefs().getString("gSort", "star");
        for (int i = 0; i < keys.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(cx);
            rb.setText(labels[i]);
            rb.setTextColor(Ui.TEXT);
            rb.setId(100 + i);
            rg.addView(rb);
            if (keys[i].equals(cur)) rb.setChecked(true);
        }
        box.addView(rg);
        TextView h2 = new TextView(cx);
        h2.setText("Show stars (none ticked = all)");
        h2.setTextColor(Ui.GOLD);
        h2.setTypeface(Typeface.DEFAULT_BOLD);
        h2.setPadding(0, dp(10), 0, 0);
        box.addView(h2);
        Set<String> on = new HashSet<String>();
        String sf = prefs().getString("gStars", "");
        if (!sf.isEmpty()) for (String x : sf.split(",")) on.add(x);
        final android.widget.CheckBox[] cb = new android.widget.CheckBox[7];
        LinearLayout row = new LinearLayout(cx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 1; i <= 7; i++) {
            cb[i - 1] = new android.widget.CheckBox(cx);
            cb[i - 1].setText(String.valueOf(i));
            cb[i - 1].setTextColor(Ui.TEXT);
            cb[i - 1].setChecked(on.contains(String.valueOf(i)));
            row.addView(cb[i - 1]);
        }
        box.addView(row);
        new Dlg(cx).setTitle("Sort and filter").setView(box)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int sel = rg.getCheckedRadioButtonId() - 100;
                        StringBuilder st = new StringBuilder();
                        for (int i = 0; i < 7; i++) if (cb[i].isChecked()) st.append(st.length() > 0 ? "," : "").append(i + 1);
                        prefs().edit().putString("gSort", keys[Math.max(0, Math.min(2, sel))]).putString("gStars", st.toString()).apply();
                        apply.run();
                    }
                }).setNegativeButton("Cancel", null).show();
    }
}
