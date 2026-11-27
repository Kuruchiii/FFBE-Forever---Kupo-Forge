package com.ffbeforever.saveedit;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decision log viewer (Gambit tab). The game script keeps the last 500 Gambit decisions in memory (switch "Decision log" in the Patch tab);
 * this pulls them with the "dlog" command, keeps a copy on disk so they can still be read after the game closed, and shows them as a list.
 */
@SuppressWarnings("unchecked")
final class DecisionLog {
    private static final int OKC = 0xff5cc46c, NOC = 0xffe06060, OFFC = 0xff7a8196;

    private final Context cx;
    private final LinearLayout list;
    private final TextView info;
    private final List<Map<String, Object>> all = new ArrayList<Map<String, Object>>();
    private String unitF = "";      // "" = all units
    private int showF = 0;          // 0 all, 1 fired a rule, 2 nothing matched
    private AlertDialog dlg;

    private DecisionLog(Context c) {
        cx = c;
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        info = new TextView(c);
        info.setTextColor(Ui.DIM);
        info.setTextSize(12);
    }

    static void open(Context c) {
        final DecisionLog d = new DecisionLog(c);
        d.show();
        d.load();
    }

    private File cache() { return new File(cx.getFilesDir(), "decision_log.json"); }

    private int dp(float v) { return Ui.dp(cx, v); }

    private Button btn(String s, View.OnClickListener on) {
        Button b = new Button(cx);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Ui.CREAM);
        b.setBackground(Ui.buttonBg(cx));
        b.setOnClickListener(on);
        return b;
    }

    private void show() {
        LinearLayout box = new LinearLayout(cx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(6), dp(12), dp(2));
        TextView hint = new TextView(cx);
        hint.setText("What Gambit looked at and chose, newest first. Needs the Decision log switch (Patch tab) and Gambit on. Tap a row for every rule.");
        hint.setTextColor(Ui.TEXT);
        hint.setTextSize(12);
        box.addView(hint);
        LinearLayout f = new LinearLayout(cx);
        f.setOrientation(LinearLayout.HORIZONTAL);
        final Button ub = btn("Unit: all  ▾", null), sb = btn("Show: all  ▾", null);
        ub.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickUnit(ub); }
        });
        sb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final String[] o = {"All decisions", "A rule fired", "Nothing matched"};
                new Dlg(cx).setTitle("Show").setItems(o, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { showF = w; sb.setText("Show: " + new String[]{"all", "fired", "nothing matched"}[w] + "  ▾"); fill(); }
                }).show();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        f.addView(ub, lp);
        f.addView(sb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(f);
        LinearLayout g = new LinearLayout(cx);
        g.setOrientation(LinearLayout.HORIZONTAL);
        g.addView(btn("Refresh", new View.OnClickListener() { public void onClick(View v) { load(); } }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        g.addView(btn("Clear", new View.OnClickListener() { public void onClick(View v) { clear(); } }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(g);
        box.addView(info);
        ScrollView sv = new ScrollView(cx);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));
        dlg = new Dlg(cx).setTitle("Decision log").setView(box).setPositiveButton("Close", null).create();
        dlg.show();
        Dlg.style(dlg);
    }

    private void pickUnit(final Button ub) {
        final List<String> names = new ArrayList<String>(), keys = new ArrayList<String>();
        names.add("All units"); keys.add("");
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (Map<String, Object> d : all) seen.add(unitName(d) + "\u0001" + Editor.str(d.get("key")));
        for (String s : seen) { String[] p = s.split("\u0001", 2); names.add(p[0]); keys.add(p.length > 1 ? p[1] : ""); }
        new Dlg(cx).setTitle("Unit").setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { unitF = keys.get(w); ub.setText("Unit: " + (w == 0 ? "all" : names.get(w)) + "  ▾"); fill(); }
        }).show();
    }

    private void load() {
        info.setText("Reading the game...");
        new Thread(new Runnable() {
            public void run() {
                String raw = null, note = "";
                try {
                    raw = Speed.sendAll("dlog", 10000);
                    if (raw != null && raw.startsWith("[")) { java.io.FileOutputStream o = new java.io.FileOutputStream(cache()); o.write(raw.getBytes("UTF-8")); o.close(); }
                    else raw = null;
                } catch (Exception e) { raw = null; }
                if (raw == null) {
                    try {
                        File c = cache();
                        if (c.exists()) {
                            java.io.FileInputStream in = new java.io.FileInputStream(c);
                            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[8192]; int n;
                            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                            in.close();
                            raw = b.toString("UTF-8");
                            note = "Game not running: showing the copy saved " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date(c.lastModified())) + ". ";
                        }
                    } catch (Exception ignored) { }
                }
                final String r2 = raw, n2 = note;
                if (dlg == null) return;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                    public void run() { apply(r2, n2); }
                });
            }
        }).start();
    }

    private void apply(String raw, String note) {
        all.clear();
        if (raw != null) {
            try {
                Object o = Json.parse(raw);
                if (o instanceof List) for (Object e : (List<Object>) o) if (e instanceof Map) all.add((Map<String, Object>) e);
            } catch (Exception ignored) { }
        }
        Collections.reverse(all);   // newest first
        info.setText(note + all.size() + " decisions" + (all.isEmpty() ? ". Turn on Decision log (Patch tab), Install Script, restart the game and fight with Gambit on." : "."));
        fill();
    }

    private void clear() {
        new Thread(new Runnable() { public void run() { try { Speed.sendAll("dlogclear", 3000); } catch (Exception ignored) { } cache().delete(); } }).start();
        all.clear();
        info.setText("Cleared.");
        fill();
    }

    private String unitName(Map<String, Object> d) {
        String n = Editor.str(d.get("name"));
        return n.isEmpty() ? Editor.str(d.get("key")) : n;
    }

    private void fill() {
        list.removeAllViews();
        int shown = 0;
        for (final Map<String, Object> d : all) {
            boolean fired = Editor.num(d.get("fired")) > 0;
            if (!unitF.isEmpty() && !unitF.equals(Editor.str(d.get("key")))) continue;
            if (showF == 1 && !fired) continue;
            if (showF == 2 && fired) continue;
            shown++;
            LinearLayout row = new LinearLayout(cx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(8), dp(6), dp(8), dp(6));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Ui.NAVY);
            bg.setCornerRadius(dp(6));
            bg.setStroke(Math.max(1, dp(1)), 0x66d4b46a);
            row.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(3), 0, dp(3));
            TextView h = new TextView(cx);
            h.setText("Turn " + Editor.str(d.get("turn")) + "  ·  stage " + Editor.str(d.get("stage")) + "  ·  " + unitName(d) + "  ·  " + time(d));
            h.setTextColor(Ui.CREAM);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setTextSize(13);
            row.addView(h);
            TextView r = new TextView(cx);
            r.setTextSize(13);
            r.setTextColor(Ui.TEXT);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (fired) sb.append("Rule " + Editor.str(d.get("fired")) + " fired: " + firedAction(d));
            else { int s0 = sb.length(); sb.append("Nothing matched: attack"); sb.setSpan(new ForegroundColorSpan(NOC), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }
            r.setText(sb);
            row.addView(r);
            TextView m = new TextView(cx);
            m.setText(marks(d));
            m.setTextSize(12);
            row.addView(m);
            row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { detail(d); } });
            list.addView(row, lp);
        }
        if (shown == 0 && !all.isEmpty()) {
            TextView t = new TextView(cx);
            t.setText("No decisions match this filter.");
            t.setTextColor(Ui.DIM);
            list.addView(t);
        }
    }

    private String time(Map<String, Object> d) {
        try { return new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date((long) Editor.num(d.get("t")))); } catch (Exception e) { return ""; }
    }

    /** One coloured dot per rule: green fired or passed, red failed, grey disabled. */
    private CharSequence marks(Map<String, Object> d) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        Object o = d.get("rules");
        if (!(o instanceof List)) return sb;
        for (Object e : (List<Object>) o) {
            Map<String, Object> r = (Map<String, Object>) e;
            String res = Editor.str(r.get("res"));
            int col = Editor.num(r.get("off")) != 0 ? OFFC : res.equals("fired") ? OKC : NOC;
            int s0 = sb.length();
            sb.append(Editor.str(r.get("n"))).append(res.equals("fired") ? "✓ " : Editor.num(r.get("off")) != 0 ? "– " : "✗ ");
            sb.setSpan(new ForegroundColorSpan(col), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private String firedAction(Map<String, Object> d) {
        Object o = d.get("rules");
        if (o instanceof List) for (Object e : (List<Object>) o) {
            Map<String, Object> r = (Map<String, Object>) e;
            if ("fired".equals(Editor.str(r.get("res")))) return act(r);
        }
        return Editor.str(d.get("act"));
    }

    /** Readable action of a rule: the saved name, else the game's skill name from the id. */
    private String act(Map<String, Object> r) {
        String a = Editor.str(r.get("a")), an = Editor.str(r.get("an"));
        if (a.equals("defend")) return "Defend";
        if (a.equals("lb")) return "Limit Burst";
        if (a.equals("summon")) return "Cast summon";
        if (a.isEmpty() || a.equals("attack")) return "Attack";
        int c = a.indexOf(':');
        String id = c > 0 ? a.substring(c + 1) : a;
        if (an.isEmpty() && a.startsWith("skill:")) { an = SkillNames.aNames.get(id); if (an == null) an = SkillNames.mNames.get(id); }
        if (an == null || an.isEmpty()) an = a;
        if (a.startsWith("dual:")) return "Dual cast: " + an;
        if (a.startsWith("esper:")) return "Cast: " + an + " (esper)";
        if (a.startsWith("item:")) return "Use item: " + an;
        return "Cast: " + an;
    }

    private String plain(String v) {
        if (v == null || v.isEmpty()) return "";
        String[][] m = {{"hp% ", "HP % "}, {"hp ", "HP "}, {"mp% ", "MP % "}, {"mp ", "MP "}, {"lb% ", "limit gauge % "}, {"avg ", "average "}, {"ko ", "KO'd allies "},
                {"foes ", "enemies left "}, {"summon% ", "summon gauge % "}, {"turn ", "turn "}, {"raw ", "resistances (id=value) "}};
        for (String[] p : m) if (v.startsWith(p[0])) return p[1] + v.substring(p[0].length()).replace('/', ',');
        return v;
    }

    private void detail(Map<String, Object> d) {
        LinearLayout box = new LinearLayout(cx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(14), dp(4));
        TextView head = new TextView(cx);
        head.setTextColor(Ui.TEXT);
        head.setTextSize(13);
        head.setText(unitName(d) + ", turn " + Editor.str(d.get("turn")) + " (game turn " + Editor.str(d.get("game")) + "), stage " + Editor.str(d.get("stage")) + "\n"
                + "HP " + Editor.str(d.get("hp")) + "%, MP " + Editor.str(d.get("mp")) + "%, limit gauge " + Editor.str(d.get("lb")) + "%, summon gauge " + Editor.str(d.get("sum")) + "/10, enemies left "
                + Editor.str(d.get("foes")) + ", KO'd allies " + Editor.str(d.get("ko")));
        box.addView(head);
        Object o = d.get("rules");
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (o instanceof List) for (Object e : (List<Object>) o) {
            Map<String, Object> r = (Map<String, Object>) e;
            String res = Editor.str(r.get("res"));
            boolean off = Editor.num(r.get("off")) != 0;
            int col = off ? OFFC : res.equals("fired") ? OKC : NOC;
            int s0 = sb.length();
            sb.append("\nRule ").append(Editor.str(r.get("n"))).append(off ? "  disabled (skipped)" : res.equals("fired") ? "  FIRED" : res.equals("blocked") ? "  conditions met, but the action could not run" : "  not met");
            sb.setSpan(new ForegroundColorSpan(col), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (off) continue;
            Object cs = r.get("cs");
            if (cs instanceof List) for (Object ce : (List<Object>) cs) {
                Map<String, Object> c = (Map<String, Object>) ce;
                Object rr = c.get("r");
                String line = rr instanceof Map ? Gambit.condLine((Map<String, Object>) rr) : "?";
                String val = plain(Editor.str(c.get("val")));
                int s1 = sb.length();
                sb.append("\n   ").append(Editor.num(c.get("ok")) != 0 ? "✓ " : "✗ ").append(line);
                if (!val.isEmpty()) sb.append("   [read: " + val + "]");
                sb.setSpan(new ForegroundColorSpan(Editor.num(c.get("ok")) != 0 ? OKC : NOC), s1, s1 + 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.append("\n   Action: ").append(act(r));
        }
        if (Editor.num(d.get("fired")) == 0) sb.append("\n\nNo rule matched, so the general bot attacked.");
        else sb.append("\n\nTarget: the game picks it.");
        TextView body = new TextView(cx);
        body.setTextColor(Ui.TEXT);
        body.setTextSize(13);
        body.setText(sb);
        box.addView(body);
        ScrollView sv = new ScrollView(cx);
        sv.addView(box);
        AlertDialog x = new Dlg(cx).setTitle("Decision").setView(sv).setPositiveButton("Close", null).create();
        x.show();
        Dlg.style(x);
    }
}
