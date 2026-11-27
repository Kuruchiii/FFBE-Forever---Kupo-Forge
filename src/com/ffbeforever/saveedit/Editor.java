package com.ffbeforever.saveedit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Edits an FFBE Forever save (version 2) in memory. Rules come from the game's local server
 * (source/server/player_state.py): level is authoritative, exp is a residual (0 is always valid),
 * total_exp must be >= exp, a level above the cap is clamped on load, TMR rewards are
 * "kind:id:count" with equipment (21) in inventory.equipment and materia (22) in inventory.items,
 * and each claimed reward is recorded as "0:kind:id:1" in affinity_reward_claimed.
 */
@SuppressWarnings("unchecked")
public final class Editor {
    /** Level cap for each star (index = star). */
    static final int[] CAP = {0, 15, 30, 40, 60, 80, 100, 120};

    public final Db db;
    public final Map<String, Object> save;
    public final List<Map<String, Object>> roster;
    /** total_exp seen on maxed units in this save, keyed "star:expPattern". */
    private final Map<String, Long> maxedExp = new HashMap<String, Long>();
    public boolean dirty;
    private final boolean endNl;

    public Editor(Db db, String text) {
        this.db = db;
        endNl = text.endsWith("\n");
        Object root = Json.parse(text);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Not a save file");
        save = (Map<String, Object>) root;
        Object ver = save.get("version");
        if (!(ver instanceof Json.Num) || ((Json.Num) ver).asLong() != 2 || !(save.get("roster") instanceof List))
            throw new IllegalArgumentException("Not an FFBE Forever version 2 save");
        roster = new ArrayList<Map<String, Object>>();
        for (Object o : (List<Object>) save.get("roster")) roster.add((Map<String, Object>) o);
        // Known maxed values (pattern 20) from the owner's save; refined from this save below.
        maxedExp.put("4:20", 548366L);
        maxedExp.put("5:20", 1137652L);
        for (Map<String, Object> u : roster) {
            String mid = str(u.get("master_id"));
            int st = star(mid);
            Db.Star s = db.starOf.get(mid);
            if (st > 0 && st < CAP.length && num(u.get("level")) == CAP[st] && s != null) {
                String k = st + ":" + s.expPattern;
                long t = num(u.get("total_exp"));
                Long have = maxedExp.get(k);
                if (have == null || t > have) maxedExp.put(k, t);
            }
        }
    }

    public String text() { return Json.write(save) + (endNl ? "\n" : ""); }

    // ---------- helpers ----------

    static String str(Object o) {
        if (o == null || o == Json.NULL) return "";
        return o instanceof String ? (String) o : o.toString();
    }

    static long num(Object o) {
        if (o instanceof Json.Num) return ((Json.Num) o).asLong();
        if (o instanceof String) { try { return Long.parseLong((String) o); } catch (NumberFormatException e) { return 0; } }
        return 0;
    }

    /** Star of a master id: the last digit (e.g. 204001405 = 5 star). */
    static int star(String mid) {
        if (mid.length() < 2) return 0;
        char c = mid.charAt(mid.length() - 1);
        return (c >= '1' && c <= '7') ? c - '0' : 0;
    }

    public String name(Map<String, Object> u) {
        Db.Unit d = db.byMaster.get(str(u.get("master_id")));
        if (d != null) return d.name;
        String mid = str(u.get("master_id"));
        // an id we do not know yet (a newer variant): name it after the character, so search finds it
        if (mid.length() >= 6) {
            String fam = mid.substring(0, 5);
            for (Db.Unit o : db.units) if (o.base.startsWith(fam)) return o.name + " (variant " + mid + ")";
        }
        return "Unknown " + mid;
    }

    /** True when the unit wears any equipment or materia. */
    public boolean hasGear(Map<String, Object> u) {
        for (String f : WORN[0]) {
            Object g = u.get(f);
            if (g instanceof Map) { for (Object o : ((Map<String, Object>) g).values()) if (worn(o)) return true; }
        }
        for (String f : WORN[1]) {
            Object g = u.get(f);
            if (g instanceof List) { for (Object o : (List<Object>) g) if (worn(o)) return true; }
            else if (g instanceof Map) { for (Object o : ((Map<String, Object>) g).values()) if (worn(o)) return true; }
        }
        return false;
    }

    private static boolean worn(Object o) { String x = str(o); return !x.isEmpty() && !x.equals("0"); }

    public int tmrPercent(Map<String, Object> u) {
        Object ap = u.get("affinity_points");
        if (!(ap instanceof List) || ((List<Object>) ap).isEmpty()) return 0;
        return (int) (num(((List<Object>) ap).get(0)) / 10);
    }

    public String label(Map<String, Object> u) {
        String mid = str(u.get("master_id"));
        return name(u) + "  " + star(mid) + "★  Lv " + num(u.get("level")) + "  TMR " + tmrPercent(u) + "%";
    }

    /** Ids of roster rows used by a deck, so labels can mark them. */
    public Set<String> partyIds() {
        Set<String> out = new HashSet<String>();
        Object p = save.get("party");
        if (!(p instanceof Map)) return out;
        Object decks = ((Map<String, Object>) p).get("decks");
        if (!(decks instanceof List)) return out;
        for (Object d : (List<Object>) decks) {
            if (!(d instanceof Map)) continue;
            Object ms = ((Map<String, Object>) d).get("members");
            if (!(ms instanceof List)) continue;
            for (Object m : (List<Object>) ms)
                if (m instanceof Map) out.add(str(((Map<String, Object>) m).get("instance_id")));
        }
        return out;
    }

    // ---------- 1. max level ----------

    /** Level to the cap of the unit's current star, exp 0, LB to its max when maxLb. Returns true if changed. */
    public boolean maxLevel(Map<String, Object> u, boolean maxLb) {
        String mid = str(u.get("master_id"));
        int st = star(mid);
        if (st < 1 || st >= CAP.length) return false;
        Db.Star s = db.starOf.get(mid);
        boolean changed = false;
        if (num(u.get("level")) != CAP[st]) { u.put("level", new Json.Num(CAP[st])); changed = true; }
        if (num(u.get("exp")) != 0) { u.put("exp", new Json.Num(0)); changed = true; }
        Long known = s != null ? maxedExp.get(st + ":" + s.expPattern) : null;
        long total = num(u.get("total_exp"));
        if (known != null && total < known) { u.put("total_exp", new Json.Num(known)); changed = true; }
        if (maxLb && s != null && s.lbMax > 0 && num(u.get("lb_level")) != s.lbMax) {
            u.put("lb_level", new Json.Num(s.lbMax));
            changed = true;
        }
        if (changed) dirty = true;
        return changed;
    }

    // ---------- 2. stars ----------

    /** Result of setStars: the star the unit ended at, or -1 when the unit is not in the database. */
    public int setStars(Map<String, Object> u, int want) {
        String mid = str(u.get("master_id"));
        Db.Unit d = db.byMaster.get(mid);
        if (d == null) return -1;
        int target = Math.max(d.minStar(), Math.min(want, d.maxStar()));
        Db.Star s = d.stars.get(target);
        if (s == null) {           // a gap in the data: take the highest star at or below the target
            Integer k = d.stars.floorKey(target);
            if (k == null) return -1;
            s = d.stars.get(k);
            target = k;
        }
        int cur = star(mid);
        if (s.id.equals(mid)) return cur;
        u.put("master_id", s.id);
        Object gr = u.get("granted_rarity");
        if (gr instanceof Json.Num) u.put("granted_rarity", new Json.Num(target));
        long lv = num(u.get("level"));
        if (lv > CAP[target]) {
            u.put("level", new Json.Num(CAP[target]));
            u.put("exp", new Json.Num(0));
        }
        long lb = num(u.get("lb_level"));
        if (s.lbMax > 0 && lb > s.lbMax) u.put("lb_level", new Json.Num(s.lbMax));
        if (s.lbMax > 0 && lb < 1) u.put("lb_level", new Json.Num(1));
        addToDictionary(s.id);
        dirty = true;
        return target;
    }

    // ---------- Bestiary (monster dictionary) ----------

    /** Monster ids this save has as defeated (key monster_dictionary, a list of id strings). */
    public Set<String> monsterIds() {
        Set<String> out = new HashSet<String>();
        Object d = save.get("monster_dictionary");
        if (d instanceof List) for (Object o : (List<Object>) d) out.add(str(o));
        return out;
    }

    // ---------- story missions and espers (tables come from the game, see Tables) ----------

    private Map<String, Object> progress() {
        Object p = save.get("progress");
        if (!(p instanceof Map)) { p = new LinkedHashMap<String, Object>(); save.put("progress", p); }
        return (Map<String, Object>) p;
    }

    private List<Object> list(Map<String, Object> m, String k) {
        Object o = m.get(k);
        if (!(o instanceof List)) { o = new ArrayList<Object>(); m.put(k, o); }
        return (List<Object>) o;
    }

    private Map<String, Object> map(Map<String, Object> m, String k) {
        Object o = m.get(k);
        if (!(o instanceof Map)) { o = new LinkedHashMap<String, Object>(); m.put(k, o); }
        return (Map<String, Object>) o;
    }

    public boolean missionDone(String id) {
        Object o = progress().get("cleared_missions");
        if (o instanceof List) for (Object x : (List<Object>) o) if (id.equals(str(x))) return true;
        return false;
    }

    /** How many of this mission's stars are marked cleared. */
    public int starsDone(Tables.Mission m) {
        Object o = progress().get("cleared_challenges");
        if (!(o instanceof Map)) return 0;
        Object l = ((Map<String, Object>) o).get(m.id);
        if (!(l instanceof List)) return 0;
        int n = 0;
        for (String[] c : m.ch) for (Object x : (List<Object>) l) if (c[0].equals(str(x))) { n++; break; }
        return n;
    }

    private boolean has(List<Object> l, String v) {
        for (Object x : l) if (v.equals(str(x))) return true;
        return false;
    }

    private void addSwitch(String type, int idx) {
        List<Object> l = list(map(progress(), "switches"), type);
        for (Object x : l) if (num(x) == idx) return;
        l.add(new Json.Num(idx));
        java.util.Collections.sort(l, new java.util.Comparator<Object>() {
            public int compare(Object a, Object b) { return Long.compare(num(a), num(b)); }
        });
        dirty = true;
    }

    /** Marks a mission cleared with all stars, opens its story switches, and pays star lapis not paid before. Returns the lapis added. */
    public long completeMission(Tables.Mission m) {
        List<Object> cm = list(progress(), "cleared_missions");
        if (!has(cm, m.id)) { cm.add(m.id); dirty = true; }
        for (String[] s : m.sw) {
            boolean ok = "101".equals(s[0]) || "9004".equals(s[0]) || (m.main && ("103".equals(s[0]) || "200".equals(s[0]) || "201".equals(s[0]) || "9002".equals(s[0])));
            if (ok) addSwitch(s[0], Integer.parseInt(s[1]));
        }
        long lapis = 0;
        Map<String, Object> cc = map(progress(), "cleared_challenges"), pc = map(progress(), "paid_challenges");
        List<Object> cl = list(cc, m.id), pl = list(pc, m.id);
        for (String[] c : m.ch) {
            if (!has(cl, c[0])) { cl.add(c[0]); dirty = true; }
            if (!has(pl, c[0])) {
                pl.add(c[0]);
                dirty = true;
                for (String part : c[1].split(",")) {
                    String[] f = part.trim().split(":");
                    if (f.length == 3 && "50".equals(f[0])) { try { lapis += Long.parseLong(f[2]); } catch (NumberFormatException ignored) { } }
                }
            }
        }
        payFirstClear(m);
        if (lapis > 0) {
            Map<String, Object> cur = map(save, "currencies");
            cur.put("lapis_free", new Json.Num(num(cur.get("lapis_free")) + lapis));
        }
        return lapis;
    }

    // ---------- gambit (battle bot rules, kept in the save under "gambit": unit base id -> list of rules) ----------

    public static final int GAMBIT_MAX = 12;

    /** Presets: save key "gambit_presets" = {"active": name, "list": {name: {base id: rules}}}. An old plain "gambit" map becomes the preset "Default". */
    private Map<String, Object> presetRoot() {
        Object o = save.get("gambit_presets");
        if (!(o instanceof Map)) {
            Map<String, Object> r = new LinkedHashMap<String, Object>(), l = new LinkedHashMap<String, Object>();
            Object old = save.remove("gambit");
            l.put("Default", old instanceof Map ? old : new LinkedHashMap<String, Object>());
            r.put("active", "Default");
            r.put("list", l);
            save.put("gambit_presets", r);
            o = r;
        }
        Map<String, Object> r = (Map<String, Object>) o;
        if (!(r.get("list") instanceof Map)) r.put("list", new LinkedHashMap<String, Object>());
        Map<String, Object> l = (Map<String, Object>) r.get("list");
        if (l.isEmpty()) l.put("Default", new LinkedHashMap<String, Object>());
        if (!l.containsKey(str(r.get("active")))) r.put("active", l.keySet().iterator().next());
        return r;
    }

    /** The whole preset set (for the app's own copy). */
    public Map<String, Object> presetsData() { return presetRoot(); }

    /** Takes the app's own copy: its presets replace same-named ones in the save, other presets stay, its choice of active preset wins. */
    public void presetsAdopt(Map<String, Object> local) {
        if (local == null || !(local.get("list") instanceof Map)) return;
        String before = Json.write(presetRoot());
        Map<String, Object> mine = (Map<String, Object>) presetRoot().get("list");
        for (Map.Entry<String, Object> x : ((Map<String, Object>) local.get("list")).entrySet())
            if (x.getValue() instanceof Map) mine.put(x.getKey(), deepCopy(x.getValue()));
        String a = str(local.get("active"));
        if (mine.containsKey(a)) presetRoot().put("active", a);
        if (!before.equals(Json.write(presetRoot()))) dirty = true;
    }

    public List<String> presetNames() { return new ArrayList<String>(((Map<String, Object>) presetRoot().get("list")).keySet()); }

    public String presetActive() { return str(presetRoot().get("active")); }

    public void presetSelect(String name) {
        if (presetNames().contains(name)) { presetRoot().put("active", name); dirty = true; }
    }

    private String uniqueName(String want) {
        String w = want.trim().isEmpty() ? "Preset" : want.trim();
        List<String> n = presetNames();
        String r = w;
        for (int i = 2; n.contains(r); i++) r = w + " " + i;
        return r;
    }

    private static Object deepCopy(Object o) {
        if (o instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) m.put(e.getKey(), deepCopy(e.getValue()));
            return m;
        }
        if (o instanceof List) {
            List<Object> l = new ArrayList<Object>();
            for (Object x : (List<Object>) o) l.add(deepCopy(x));
            return l;
        }
        return o;
    }

    /** New preset (empty, or a copy of the active one) that becomes active. Returns its name. */
    public String presetNew(String name, boolean copyActive) {
        String n = uniqueName(name);
        Map<String, Object> l = (Map<String, Object>) presetRoot().get("list");
        l.put(n, copyActive ? deepCopy(gambit()) : new LinkedHashMap<String, Object>());
        presetRoot().put("active", n);
        dirty = true;
        return n;
    }

    public String presetRename(String name) {
        Map<String, Object> r = presetRoot(), l = (Map<String, Object>) r.get("list");
        String old = str(r.get("active"));
        String want = name.trim();
        if (want.isEmpty() || want.equals(old)) return old;
        String n = uniqueName(want);
        Map<String, Object> nl = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : l.entrySet()) nl.put(e.getKey().equals(old) ? n : e.getKey(), e.getValue());
        r.put("list", nl);
        r.put("active", n);
        dirty = true;
        return n;
    }

    /** Deletes the active preset (the last one cannot be deleted). */
    public boolean presetDelete() {
        Map<String, Object> r = presetRoot(), l = (Map<String, Object>) r.get("list");
        if (l.size() < 2) return false;
        l.remove(str(r.get("active")));
        r.put("active", l.keySet().iterator().next());
        dirty = true;
        return true;
    }

    /** The rule lists of the active preset, one per character (key = the unit's 8-digit base id). */
    public Map<String, Object> gambit() {
        Map<String, Object> r = presetRoot(), l = (Map<String, Object>) r.get("list");
        return (Map<String, Object>) l.get(str(r.get("active")));
    }

    public List<Object> gambitRules(String base) {
        Map<String, Object> g = gambit();
        Object l = g.get(base);
        if (!(l instanceof List)) { l = new ArrayList<Object>(); g.put(base, l); dirty = true; }
        return (List<Object>) l;
    }

    public boolean hasGambit() { return !gambit().isEmpty(); }

    public void gambitRemove(String base) { gambit().remove(base); dirty = true; }

    /** All rules as a small JSON file text, for sharing. */
    public String gambitExport() {
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("format", "ffbe-gambit");
        o.put("version", new Json.Num(2));
        o.put("name", presetActive());
        o.put("gambit", gambit());
        return Json.write(o) + "\n";
    }

    /** Adds the file as a new preset (made active). Returns the number of characters read. */
    public int gambitImport(String text) throws Exception {
        Object root = Json.parse(text);
        if (!(root instanceof Map) || !"ffbe-gambit".equals(str(((Map<String, Object>) root).get("format")))) throw new Exception("This is not a gambit file.");
        Object g = ((Map<String, Object>) root).get("gambit");
        if (!(g instanceof Map)) throw new Exception("The gambit file has no rules.");
        int n = 0;
        String pn = str(((Map<String, Object>) root).get("name"));
        presetNew(pn.isEmpty() ? "Imported" : pn, false);
        for (Map.Entry<String, Object> e : ((Map<String, Object>) g).entrySet()) {
            if (!(e.getValue() instanceof List)) continue;
            List<Object> rules = new ArrayList<Object>();
            for (Object r : (List<Object>) e.getValue()) {
                if (!(r instanceof Map) || rules.size() >= GAMBIT_MAX) continue;
                Map<String, Object> src = (Map<String, Object>) r, rule = new LinkedHashMap<String, Object>();
                for (String k : new String[]{"c", "o", "v", "t", "op", "n", "a", "an"}) if (src.get(k) != null) rule.put(k, str(src.get(k)));
                if (Boolean.TRUE.equals(src.get("off"))) rule.put("off", Boolean.TRUE);
                if (src.get("and") instanceof List) {
                    List<Object> extra = new ArrayList<Object>();
                    for (Object x : (List<Object>) src.get("and")) {
                        if (!(x instanceof Map) || extra.size() >= 3) continue;
                        Map<String, Object> cc = new LinkedHashMap<String, Object>();
                        for (String k : new String[]{"c", "o", "v", "t", "op", "n"}) if (((Map<String, Object>) x).get(k) != null) cc.put(k, str(((Map<String, Object>) x).get(k)));
                        if (cc.get("c") != null) extra.add(cc);
                    }
                    if (!extra.isEmpty()) rule.put("and", extra);
                }
                if (rule.get("c") == null || rule.get("a") == null) continue;
                rules.add(rule);
            }
            gambit().put(e.getKey(), rules);
            n++;
        }
        dirty = true;
        return n;
    }

    /** What the last completeMission paid as a first-clear reward, for the screen ("" when nothing). */
    public String rewardNote = "";

    /**
     * Pays a mission's first-clear reward (F_MISSION_MST field 1x4bG2J8, "kind:id:count:rate" entries) once, the way the game's server
     * does, and writes the same claim record (exvius_repeat.mission_clear_claims) so nothing is paid twice. Kinds handled: 10 unit
     * (skipped if you already own it), 20/23 items, 21 equipment, 30 gil, 50 lapis, 51 friend points. Other kinds stay unpaid and
     * are listed as withheld.
     */
    private void payFirstClear(Tables.Mission m) {
        rewardNote = "";
        if (m.fc == null || m.fc.trim().isEmpty()) return;
        Map<String, Object> er = map(save, "exvius_repeat");
        Map<String, Object> claims = map(er, "mission_clear_claims");
        if (claims.containsKey(m.id)) return;
        List<Object> paid = new ArrayList<Object>(), withheld = new ArrayList<Object>();
        StringBuilder note = new StringBuilder();
        Map<String, Object> cur = map(save, "currencies");
        for (String entry : m.fc.split(",")) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;
            String[] f = entry.split(":");
            int kind; long id, cnt;
            try { kind = Integer.parseInt(f[0].trim()); id = Long.parseLong(f[1].trim()); cnt = Long.parseLong(f[2].trim()); }
            catch (Exception e) { withheld.add(entry); continue; }
            if (cnt <= 0) continue;
            String row = kind + ":" + id + ":" + cnt;
            if (kind == 30 || kind == 50 || kind == 51) {
                String key = kind == 30 ? "gil" : kind == 50 ? "lapis_free" : "friend_point";
                cur.put(key, new Json.Num(num(cur.get(key)) + cnt));
                dirty = true;
                paid.add(row);
                note.append(" ").append(cnt).append(kind == 30 ? " gil" : kind == 50 ? " lapis" : " friend points").append(",");
            } else if (kind == 20 || kind == 23 || kind == 21) {
                grant(kind, id, cnt);
                paid.add(row);
                note.append(" item ").append(id).append(" x").append(cnt).append(",");
            } else if (kind == 10) {
                Db.Unit d = db.byMaster.get(String.valueOf(id));
                if (d == null) { withheld.add(entry); continue; }
                if (ownedCount(d) > 0) { paid.add(row); note.append(" ").append(d.name).append(" (already owned),"); continue; }
                int star = d.minStar();
                for (Map.Entry<Integer, Db.Star> e : d.stars.entrySet()) if (String.valueOf(id).equals(e.getValue().id)) star = e.getKey();
                for (long k = 0; k < cnt; k++) addUnit(d, star);
                paid.add(row);
                note.append(" unit ").append(d.name).append(",");
            } else {
                withheld.add(entry);
            }
        }
        Map<String, Object> claim = new LinkedHashMap<String, Object>();
        claim.put("source", "1x4bG2J8");
        claim.put("paid", paid);
        claim.put("withheld", withheld);
        claims.put(m.id, claim);
        dirty = true;
        rewardNote = note.toString().replaceAll(",$", "").trim() + (withheld.isEmpty() ? "" : " (" + withheld.size() + " reward(s) of other kinds not paid)");
    }

    /** Takes a mission back to not cleared (stars removed). Switches and paid lapis are left as they are. */
    public void incompleteMission(Tables.Mission m) {
        List<Object> cm = list(progress(), "cleared_missions");
        for (int i = cm.size() - 1; i >= 0; i--) if (m.id.equals(str(cm.get(i)))) { cm.remove(i); dirty = true; }
        Map<String, Object> cc = map(progress(), "cleared_challenges");
        if (cc.remove(m.id) != null) dirty = true;
    }

    private Map<String, Object> beastRow(String id) {
        for (Object o : list(save, "beasts")) {
            Map<String, Object> b = (Map<String, Object>) o;
            if (id.equals(str(b.get("esper_id")))) return b;
        }
        return null;
    }

    public boolean ownsEsper(String id) { return beastRow(id) != null; }

    public int espersOwnedLevel(String id) { Map<String, Object> b = beastRow(id); return b == null ? 0 : (int) num(b.get("level")); }

    /** Adds or updates an esper at a star level and level, with the CP that level has earned, and opens its story switches. */
    public void setEsper(Tables.Beast b, int level) {
        if (level < 1) level = 1;
        if (level > b.maxLv) level = b.maxLv;
        long cp = 0;
        String csv = Tables.cpAdd.get(b.cpKey);
        if (csv != null) {
            String[] v = csv.split(",");
            for (int lv = 2; lv <= level; lv++) if (lv - 1 < v.length && v[lv - 1].trim().length() > 0) { try { cp += Long.parseLong(v[lv - 1].trim()); } catch (NumberFormatException ignored) { } }
        }
        Map<String, Object> row = beastRow(b.id);
        if (row == null) {
            row = new LinkedHashMap<String, Object>();
            row.put("esper_id", b.id);
            row.put("exp", new Json.Num(0));
            row.put("reset_count", new Json.Num(0));
            row.put("board", "0");
            list(save, "beasts").add(row);
        }
        row.put("rarity", new Json.Num(b.rar));
        row.put("level", new Json.Num(level));
        row.put("cp", new Json.Num(cp));
        if (b.own != null) addSwitch(b.own[0], Integer.parseInt(b.own[1]));
        if (level >= b.maxLv && b.cap != null) addSwitch(b.cap[0], Integer.parseInt(b.cap[1]));
        dirty = true;
    }

    public void setEsperCp(String id, long cp) {
        Map<String, Object> row = beastRow(id);
        if (row != null) { row.put("cp", new Json.Num(cp)); dirty = true; }
    }

    /** True when the save has a Bestiary choice list. Without it the patched game shows every monster. */
    public boolean hasMonsterKey() { return save.get("monster_dictionary") instanceof List; }

    /** Empties the Bestiary choice list (the key stays, so the patched game shows no monster). */
    public void clearMonsters() { save.put("monster_dictionary", new ArrayList<Object>()); dirty = true; }

    /** Adds the ids to the save's bestiary. Returns how many were new. */
    public int unlockMonsters(java.util.Collection<String> ids) {
        Object d = save.get("monster_dictionary");
        if (!(d instanceof List)) { d = new ArrayList<Object>(); save.put("monster_dictionary", d); }
        List<Object> l = (List<Object>) d;
        Set<String> have = monsterIds();
        int n = 0;
        for (String id : ids) if (have.add(id)) { l.add(id); n++; }
        if (n > 0) dirty = true;
        return n;
    }

    /** Removes the ids from the save's bestiary. Returns how many were removed. */
    public int lockMonsters(java.util.Collection<String> ids) {
        Object d = save.get("monster_dictionary");
        if (!(d instanceof List)) return 0;
        List<Object> l = (List<Object>) d;
        int n = 0;
        for (int i = l.size() - 1; i >= 0; i--) if (ids.contains(str(l.get(i)))) { l.remove(i); n++; }
        if (n > 0) dirty = true;
        return n;
    }

    private void addToDictionary(String id) {
        Object dict = save.get("unit_dictionary");
        if (!(dict instanceof List)) return;
        List<Object> l = (List<Object>) dict;
        for (Object o : l) if (id.equals(str(o))) return;
        l.add(id);
    }

    // ---------- 3. TMR ----------

    public static final int TMR_DONE = 1, TMR_ALREADY = 0, TMR_UNKNOWN = -1;

    /** Sets TMR to 100% and grants the TMR once (the claim record stops a second grant). */
    public int tmr(Map<String, Object> u) {
        Db.Unit d = db.byMaster.get(str(u.get("master_id")));
        if (d == null || d.tmrKind == 0) return TMR_UNKNOWN;
        Object apo = u.get("affinity_points");
        List<Object> ap = apo instanceof List ? (List<Object>) apo : new ArrayList<Object>();
        while (ap.size() < 2) ap.add("");
        String second = str(ap.get(1));
        boolean changed = false;
        if (!"1000".equals(str(ap.get(0)))) { ap.set(0, "1000"); changed = true; }
        if (second.isEmpty()) { ap.set(1, "0"); changed = true; }
        u.put("affinity_points", ap);
        String key = "0:" + d.tmrKind + ":" + d.tmrId + ":1";
        Object co = u.get("affinity_reward_claimed");
        List<Object> claimed = co instanceof List ? (List<Object>) co : new ArrayList<Object>();
        boolean has = false;
        for (Object o : claimed) if (key.equals(str(o))) has = true;
        if (!has) {
            claimed.add(key);
            u.put("affinity_reward_claimed", claimed);
            grant(d.tmrKind, d.tmrId, 1);
            changed = true;
        }
        if (changed) dirty = true;
        return changed ? TMR_DONE : TMR_ALREADY;
    }

    /** Roster units that got this TMR item as their reward (claimed, or at 100%). */
    public List<Map<String, Object>> tmrClaimers(int kind, long id) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String key = "0:" + kind + ":" + id + ":1";
        for (Map<String, Object> u : roster) {
            Db.Unit d = db.byMaster.get(str(u.get("master_id")));
            if (d == null || d.tmrKind != kind || d.tmrId != id) continue;
            boolean has = false;
            Object co = u.get("affinity_reward_claimed");
            if (co instanceof List) for (Object o : (List<Object>) co) if (key.equals(str(o))) has = true;
            if (has || tmrPercent(u) >= 100) out.add(u);
        }
        return out;
    }

    /** Unclaims the TMR reward on every claimer: claim record removed, TMR set to 99% so the game can pay it out again. */
    public int unclaimTmr(int kind, long id) {
        String key = "0:" + kind + ":" + id + ":1";
        int n = 0;
        for (Map<String, Object> u : tmrClaimers(kind, id)) {
            Object co = u.get("affinity_reward_claimed");
            if (co instanceof List) {
                List<Object> l = (List<Object>) co;
                for (int i = l.size() - 1; i >= 0; i--) if (key.equals(str(l.get(i)))) l.remove(i);
            }
            Object apo = u.get("affinity_points");
            List<Object> ap = apo instanceof List ? (List<Object>) apo : new ArrayList<Object>();
            while (ap.size() < 2) ap.add("");
            ap.set(0, "990");
            u.put("affinity_points", ap);
            n++;
        }
        if (n > 0) dirty = true;
        return n;
    }

    // ---------- 4. items ----------

    static final long STACK_MAX = 9999;

    /** Adds count of an equipment (21) or materia (22) item. Returns the new total. */
    public long grant(int kind, long id, long count) {
        Map<String, Object> inv = (Map<String, Object>) save.get("inventory");
        if (inv == null) { inv = new LinkedHashMap<String, Object>(); save.put("inventory", inv); }
        String listKey = kind == 21 ? "equipment" : "items";
        Object lo = inv.get(listKey);
        if (!(lo instanceof List)) { lo = new ArrayList<Object>(); inv.put(listKey, lo); }
        List<Object> list = (List<Object>) lo;
        for (Object o : list) {
            Map<String, Object> it = (Map<String, Object>) o;
            if (num(it.get("item_id")) != id) continue;
            if (kind != 21 && num(it.get("item_type")) != kind) continue;
            long n = Math.min(STACK_MAX, num(it.get("count")) + count);
            it.put("count", new Json.Num(n));
            dirty = true;
            return n;
        }
        Map<String, Object> it = new LinkedHashMap<String, Object>();
        if (kind != 21) it.put("item_type", new Json.Num(kind));
        it.put("item_id", new Json.Num(id));
        long n = Math.min(STACK_MAX, count);
        it.put("count", new Json.Num(n));
        list.add(it);
        dirty = true;
        return n;
    }

    private static final String[][] WORN = {{"equipment", "shift_equipment"}, {"materia", "shift_materia"}};

    /** Every slot on a unit that holds this item: {unit, field name, slot key or list index}. */
    private List<Object[]> wearers(int kind, long id) {
        List<Object[]> out = new ArrayList<Object[]>();
        String want = String.valueOf(id);
        for (Map<String, Object> u : roster) {
            for (String f : WORN[kind == 21 ? 0 : 1]) {
                Object g = u.get(f);
                if (g instanceof Map) {
                    for (Map.Entry<String, Object> e : ((Map<String, Object>) g).entrySet())
                        if (want.equals(str(e.getValue()))) out.add(new Object[]{u, f, e.getKey()});
                } else if (g instanceof List) {
                    List<Object> l = (List<Object>) g;
                    for (int i = 0; i < l.size(); i++) if (want.equals(str(l.get(i)))) out.add(new Object[]{u, f, i});
                }
            }
        }
        return out;
    }

    /** Copies currently worn by units. */
    public int worn(int kind, long id) { return wearers(kind, id).size(); }

    /** Copies that are not worn: what a plain Remove can take. The game counts at least the worn copies as owned. */
    public long spare(int kind, long id) { return Math.max(0, owned(kind, id) - worn(kind, id)); }

    /** Unit names wearing this item, no repeats, in roster order. */
    public List<String> wearerNames(int kind, long id) {
        List<String> out = new ArrayList<String>();
        for (Object[] w : wearers(kind, id)) {
            String n = name((Map<String, Object>) w[0]);
            if (!out.contains(n)) out.add(n);
        }
        return out;
    }

    /**
     * Removes up to n copies. Spare copies go first. Only when unequip is true are copies taken off units
     * (their slot becomes "0") and removed too. Returns {removed, unequipped}.
     */
    public long[] remove(int kind, long id, long n, boolean unequip) {
        Map<String, Object> inv = (Map<String, Object>) save.get("inventory");
        if (inv == null) return new long[]{0, 0};
        Object lo = inv.get(kind == 21 ? "equipment" : "items");
        if (!(lo instanceof List)) return new long[]{0, 0};
        List<Object> list = (List<Object>) lo;
        List<Object[]> w = wearers(kind, id);
        long c = owned(kind, id), total = Math.max(c, w.size()), spare = Math.max(0, c - w.size());
        long fromSpare = Math.min(n, spare);
        long off = unequip ? Math.min(n - fromSpare, w.size()) : 0;
        for (int i = 0; i < off; i++) {
            Object[] x = w.get(w.size() - 1 - i);          // last wearers first
            Object g = ((Map<String, Object>) x[0]).get((String) x[1]);
            if (g instanceof Map) ((Map<String, Object>) g).put((String) x[2], "0");
            else ((List<Object>) g).set((Integer) x[2], "0");
        }
        long removed = fromSpare + off;
        if (removed == 0) return new long[]{0, 0};
        long left = total - removed;
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> it = (Map<String, Object>) list.get(i);
            if (num(it.get("item_id")) != id || (kind != 21 && num(it.get("item_type")) != kind)) continue;
            if (left <= 0) list.remove(i);
            else it.put("count", new Json.Num(left));
            break;
        }
        dirty = true;
        return new long[]{removed, off};
    }

    /** The shared enhancement-point bank (exvius_unit_points.banks["0"]): stat pots and doors are numbers in it, keyed by the game's token. */
    private Map<String, Object> bank0(boolean make) {
        Object o = save.get("exvius_unit_points");
        if (!(o instanceof Map)) { if (!make) return null; o = new LinkedHashMap<String, Object>(); save.put("exvius_unit_points", o); }
        Map<String, Object> up = (Map<String, Object>) o;
        Object b = up.get("banks");
        if (!(b instanceof Map)) { if (!make) return null; b = new LinkedHashMap<String, Object>(); up.put("banks", b); }
        Map<String, Object> bs = (Map<String, Object>) b;
        Object z = bs.get("0");
        if (!(z instanceof Map)) { if (!make) return null; z = new LinkedHashMap<String, Object>(); bs.put("0", z); }
        return (Map<String, Object>) z;
    }

    public long bank(String token) { Map<String, Object> z = bank0(false); return z == null ? 0 : num(z.get(token)); }

    public void setBank(String token, long n) {
        n = Math.max(0, Math.min(99999999L, n));
        if (bank(token) == n) return;
        bank0(true).put(token, new Json.Num(n));
        dirty = true;
    }

    /** A currency in the save: "gil" or "lapis_free". */
    public long currency(String key) { return num(map(save, "currencies").get(key)); }

    public void setCurrency(String key, long n) {
        n = Math.max(0, Math.min(9999999999L, n));
        Map<String, Object> cur = map(save, "currencies");
        if (num(cur.get(key)) != n) { cur.put(key, new Json.Num(n)); dirty = true; }
    }

    /** Sets the exact count of a material or other item row (type kind, not equipment). Adds the row when missing. */
    public void setItemCount(int kind, long id, long n) {
        n = Math.max(0, Math.min(STACK_MAX, n));
        Map<String, Object> inv = (Map<String, Object>) save.get("inventory");
        if (inv == null) { inv = new LinkedHashMap<String, Object>(); save.put("inventory", inv); }
        Object lo = inv.get("items");
        if (!(lo instanceof List)) { lo = new ArrayList<Object>(); inv.put("items", lo); }
        List<Object> list = (List<Object>) lo;
        for (Object o : list) {
            Map<String, Object> it = (Map<String, Object>) o;
            if (num(it.get("item_id")) != id || num(it.get("item_type")) != kind) continue;
            if (num(it.get("count")) != n) { it.put("count", new Json.Num(n)); dirty = true; }
            return;
        }
        if (n == 0) return;
        Map<String, Object> it = new LinkedHashMap<String, Object>();
        it.put("item_type", new Json.Num(kind));
        it.put("item_id", new Json.Num(id));
        it.put("count", new Json.Num(n));
        list.add(it);
        dirty = true;
    }

    /** Removes a roster row the way the game's drop_units does: party decks lose the member, counts are refreshed. Worn gear stays in the item counts. */
    public boolean deleteUnit(String iid) {
        boolean found = false;
        for (int i = 0; i < roster.size(); i++) {
            if (str(roster.get(i).get("instance_id")).equals(iid)) { roster.remove(i); found = true; break; }
        }
        if (!found) return false;
        List<Object> sr = (List<Object>) save.get("roster");
        for (int i = 0; i < sr.size(); i++)
            if (sr.get(i) instanceof Map && str(((Map<String, Object>) sr.get(i)).get("instance_id")).equals(iid)) { sr.remove(i); break; }
        pruneMembers(save.get("party"), iid);
        refreshCounts();
        dirty = true;
        return true;
    }

    private void pruneMembers(Object o, String iid) {
        if (o instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (e.getKey().equals("members") && e.getValue() instanceof List) {
                    List<Object> l = (List<Object>) e.getValue();
                    for (int i = l.size() - 1; i >= 0; i--)
                        if (l.get(i) instanceof Map && str(((Map<String, Object>) l.get(i)).get("instance_id")).equals(iid)) l.remove(i);
                } else pruneMembers(e.getValue(), iid);
            }
        } else if (o instanceof List) {
            for (Object x : (List<Object>) o) pruneMembers(x, iid);
        }
    }

    public long owned(int kind, long id) {
        Map<String, Object> inv = (Map<String, Object>) save.get("inventory");
        if (inv == null) return 0;
        Object lo = inv.get(kind == 21 ? "equipment" : "items");
        if (!(lo instanceof List)) return 0;
        for (Object o : (List<Object>) lo) {
            Map<String, Object> it = (Map<String, Object>) o;
            if (num(it.get("item_id")) == id && (kind == 21 || num(it.get("item_type")) == kind))
                return num(it.get("count"));
        }
        return 0;
    }

    // ---------- checks before writing ----------

    /** Problems that would make the file unsafe to import. Empty list = OK. */
    // ---------- profile ----------

    /** The player name (identity.handle_name), or "" when the save has none. */
    public String playerName() {
        Object id = save.get("identity");
        if (!(id instanceof Map)) return "";
        Object n = ((Map<String, Object>) id).get("handle_name");
        return n instanceof String ? (String) n : "";
    }

    /**
     * Why a name would be refused, or null when it is fine. Same rule as the game's own rename
     * (exvius_actions.update_profile): not blank, at most 64 characters, Python str.isprintable().
     */
    public static String nameProblem(String n) {
        if (n == null || n.trim().isEmpty()) return "The name can't be blank.";
        int len = n.codePointCount(0, n.length());
        if (len > 64) return "The name is " + len + " characters. The game allows 64.";
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ' ') continue;
            switch (Character.getType(cp)) {
                case Character.CONTROL: case Character.FORMAT: case Character.SURROGATE:
                case Character.PRIVATE_USE: case Character.UNASSIGNED: case Character.SPACE_SEPARATOR:
                case Character.LINE_SEPARATOR: case Character.PARAGRAPH_SEPARATOR:
                    return String.format("The name has a hidden or special character (U+%04X). Use letters, numbers and normal spaces.", cp);
                default:
            }
        }
        return null;
    }

    /** Sets the player name. Returns the problem (and changes nothing) when the name is refused. */
    public String setPlayerName(String n) {
        String bad = nameProblem(n);
        if (bad != null) return bad;
        Object id = save.get("identity");
        if (!(id instanceof Map)) return "This save has no identity section.";
        Map<String, Object> m = (Map<String, Object>) id;
        if (!n.equals(m.get("handle_name"))) {
            m.put("handle_name", n);
            dirty = true;
        }
        return null;
    }

    // ---------- add units ----------

    private static final Map<String, String[]> MOOGLE = new HashMap<String, String[]>();
    static {
        MOOGLE.put("904000101", new String[]{"10", ""});
        MOOGLE.put("904000104", new String[]{"50", ""});
        MOOGLE.put("904000105", new String[]{"100", ""});
        MOOGLE.put("906000104", new String[]{"", "500"});
        MOOGLE.put("906000105", new String[]{"", "1000"});
    }

    /** How many roster rows are this unit (any star). */
    public int ownedCount(Db.Unit d) {
        int n = 0;
        for (Map<String, Object> u : roster) if (db.byMaster.get(str(u.get("master_id"))) == d) n++;
        return n;
    }

    /**
     * Adds one copy of a unit at the given star (clamped to the unit's own range), the way the game's
     * grant_unit (player_state.py) does: a bare row (level 1, exp 0, LB 1 if it has one, no gear, TMR 0),
     * granted_rarity = the star, a new epoch-ms instance id above every id in use, counts.unit/storage
     * refreshed and the master id registered in unit_dictionary. Returns the new row.
     */
    public Map<String, Object> addUnit(Db.Unit d, int want) {
        int st = Math.max(d.minStar(), Math.min(d.maxStar(), want));
        Db.Star s = d.stars.get(st);
        if (s == null) {                       // a gap in the star list: take the closest star below
            Integer k = d.stars.floorKey(st);
            if (k == null) k = d.minStar();
            st = k;
            s = d.stars.get(st);
        }
        long id = System.currentTimeMillis();
        for (Map<String, Object> u : roster) {
            String iid = str(u.get("instance_id"));
            try { id = Math.max(id, Long.parseLong(iid) + 1); } catch (NumberFormatException ignored) { }
        }
        Map<String, Object> u = new java.util.LinkedHashMap<String, Object>();
        u.put("instance_id", String.valueOf(id));
        u.put("master_id", s.id);
        u.put("level", new Json.Num(1));
        u.put("exp", new Json.Num(0));
        u.put("total_exp", new Json.Num(0));
        u.put("lb_exp", new Json.Num(0));
        u.put("nv_ex_level", new Json.Num(0));
        u.put("guest", new Json.Num(0));
        u.put("storage_state", new Json.Num(0));
        u.put("favourite", new Json.Num(0));
        u.put("granted_rarity", new Json.Num(st));
        u.put("first_get_unit_id", Json.NULL);
        u.put("lb_level", new Json.Num(s.lbMax > 0 ? 1 : 0));
        u.put("equipment", new java.util.LinkedHashMap<String, Object>());
        List<Object> mat = new ArrayList<Object>();
        for (int i = 0; i < 4; i++) mat.add("0");
        u.put("materia", mat);
        u.put("vision_card", "");
        // Trust moogles carry their trust points like grant_unit's table: TMR (index 0) or STMR (index 1).
        String[] moogle = MOOGLE.get(s.id);
        List<Object> ap = new ArrayList<Object>();
        ap.add(moogle != null ? moogle[0] : "");
        ap.add(moogle != null ? moogle[1] : "");
        u.put("affinity_points", ap);
        List<Object> ai = new ArrayList<Object>(); ai.add(""); ai.add("");
        u.put("affinity_info", ai);
        ((List<Object>) save.get("roster")).add(u);
        roster.add(u);
        refreshCounts();
        Object dic = save.get("unit_dictionary");
        if (dic == null) { dic = new ArrayList<Object>(); save.put("unit_dictionary", dic); }
        if (dic instanceof List && !((List<Object>) dic).contains(s.id)) ((List<Object>) dic).add(s.id);
        dirty = true;
        return u;
    }

    /** counts.unit = roster size, counts.storage = rows in storage (player_state._refresh_derived_counts). */
    private void refreshCounts() {
        Object c = save.get("counts");
        if (!(c instanceof Map)) { c = new java.util.LinkedHashMap<String, Object>(); save.put("counts", c); }
        int storage = 0;
        for (Map<String, Object> u : roster) if (num(u.get("storage_state")) != 0) storage++;
        ((Map<String, Object>) c).put("unit", new Json.Num(roster.size()));
        ((Map<String, Object>) c).put("storage", new Json.Num(storage));
    }

    public List<String> check() {
        List<String> bad = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (Map<String, Object> u : roster) {
            String iid = str(u.get("instance_id"));
            if (!seen.add(iid)) bad.add("Instance id used twice: " + iid);
            long lv = num(u.get("level")), ex = num(u.get("exp")), tot = num(u.get("total_exp"));
            int st = star(str(u.get("master_id")));
            if (lv < 1) bad.add(name(u) + ": level below 1");
            if (st > 0 && st < CAP.length && lv > CAP[st]) bad.add(name(u) + ": level above cap");
            if (tot < ex) bad.add(name(u) + ": total_exp below exp");
        }
        if (save.get("identity") instanceof Map) {
            String bad2 = nameProblem(playerName());
            if (bad2 != null) bad.add("Player name: " + bad2);
        }
        for (String iid : partyIds())
            if (!iid.isEmpty() && !seen.contains(iid)) bad.add("A party points to a missing unit: " + iid);
        try {
            Json.parse(text());
        } catch (RuntimeException e) {
            bad.add("Output does not parse: " + e.getMessage());
        }
        return bad;
    }
}
