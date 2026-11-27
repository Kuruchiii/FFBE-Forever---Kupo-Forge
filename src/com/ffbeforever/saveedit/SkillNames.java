package com.ffbeforever.saveedit;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Skills a character can cast (bundled list) with names read from the running game (command skn, cached in a file). */
@SuppressWarnings("unchecked")
final class SkillNames {
    private static Map<String, Object> bundle;
    static final Map<String, String> aNames = new HashMap<String, String>(), mNames = new HashMap<String, String>(), iNames = new HashMap<String, String>(),
            aDesc = new HashMap<String, String>(), mDesc = new HashMap<String, String>(), iDesc = new HashMap<String, String>(), eNames = new HashMap<String, String>(), eDesc = new HashMap<String, String>();
    static final Map<String, Integer> aIcon = new HashMap<String, Integer>(), mIcon = new HashMap<String, Integer>();
    static final Map<String, String> iIcon = new HashMap<String, String>();
    static final Map<String, List<Object>> eBoard = new HashMap<String, List<Object>>();
    static final Map<String, String> eSummon = new HashMap<String, String>();
    static final java.util.Set<String> iBattle = new java.util.HashSet<String>();
    static String dbg = "";

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
        in.close();
        return b.toByteArray();
    }

    private static File cache(Context c) { return new File(c.getFilesDir(), "skill_names.json"); }

    static synchronized void init(Context c) {
        if (bundle == null) {
            try { bundle = (Map<String, Object>) Json.parse(new String(readAll(c.getAssets().open("gskills.json")), "UTF-8")); }
            catch (Exception e) { bundle = new HashMap<String, Object>(); }
            try { if (cache(c).exists()) parse(new String(readAll(new FileInputStream(cache(c))), "UTF-8")); } catch (Exception ignored) { }
        }
    }

    static boolean hasNames() { return !aNames.isEmpty() && !iNames.isEmpty() && !iBattle.isEmpty() && !eBoard.isEmpty() && !eSummon.isEmpty(); }   // a cache written before the esper tables were added has no board data: read the names again

    private static void parse(String json) throws Exception {
        Map<String, Object> m = (Map<String, Object>) Json.parse(json);
        aNames.clear(); mNames.clear(); iNames.clear(); aDesc.clear(); mDesc.clear(); iDesc.clear(); eNames.clear(); eDesc.clear(); aIcon.clear(); mIcon.clear(); iIcon.clear(); iBattle.clear(); eBoard.clear(); eSummon.clear();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("a")).entrySet()) aNames.put(e.getKey(), Editor.str(e.getValue()));
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("m")).entrySet()) mNames.put(e.getKey(), Editor.str(e.getValue()));
        if (m.get("i") instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("i")).entrySet()) iNames.put(e.getKey(), Editor.str(e.getValue()));
        String[] dk = {"ad", "md", "id"};
        List<Map<String, String>> dm = new ArrayList<Map<String, String>>();
        dm.add(aDesc); dm.add(mDesc); dm.add(iDesc);
        for (int k = 0; k < 3; k++)
            if (m.get(dk[k]) instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get(dk[k])).entrySet()) dm.get(k).put(e.getKey(), Editor.str(e.getValue()));
        if (m.get("en") instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("en")).entrySet()) eNames.put(e.getKey(), Editor.str(e.getValue()));
        if (m.get("ed") instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("ed")).entrySet()) eDesc.put(e.getKey(), Editor.str(e.getValue()));
        if (m.get("ai") instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("ai")).entrySet()) aIcon.put(e.getKey(), (int) Editor.num(e.getValue()));
        if (m.get("mi") instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("mi")).entrySet()) mIcon.put(e.getKey(), (int) Editor.num(e.getValue()));
        if (m.get("ii") instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("ii")).entrySet()) iIcon.put(e.getKey(), Editor.str(e.getValue()));
        if (m.get("ib") instanceof List) for (Object o : (List<Object>) m.get("ib")) iBattle.add(Editor.str(o));
        if (m.get("eb") instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("eb")).entrySet()) eBoard.put(e.getKey(), (List<Object>) e.getValue());
        if (m.get("es") instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("es")).entrySet()) eSummon.put(e.getKey(), Editor.str(e.getValue()));
        Object d = m.get("dbg");
        dbg = d == null ? "" : Json.write(d);
    }

    /** Asks the running game for the skill names. */
    static void fetch(Context c) throws Exception {
        String r = Speed.sendBig("skn", 120000);
        if (!r.startsWith("S ")) throw new Exception("The game did not send its skill names. Patch the game first, then open it.");
        String json = r.substring(2);
        parse(json);
        FileOutputStream o = new FileOutputStream(cache(c));
        o.write(json.getBytes("UTF-8"));
        o.close();
        if (aNames.isEmpty() && mNames.isEmpty()) throw new Exception("No skill names found. " + dbg);
    }

    /** The skill's picture (ability_N.png from the bundled icon pack), or null. */
    static android.graphics.Bitmap icon(String id) {
        Integer n = id.length() == 5 ? mIcon.get(id) : aIcon.get(id);
        if (n == null) return null;
        return Icons.ability(n);
    }

    /** The item's picture (item_NNN.png from the icon pack or imported icons), or null. */
    static android.graphics.Bitmap itemIcon(String id) {
        String n = iIcon.get(id);
        if (n == null) return null;
        return Icons.named(n);
    }

    /** Castable skills only: abilities are the 2xxxxx ids, magic is the 5-digit ids. Passives use other id ranges (1xxxxx, 9xxxxx, 7 digits). */
    static boolean active(int type, String id) {
        if (type == 1) return id.length() == 5;
        return id.length() == 6 && id.charAt(0) == '2';
    }

    /** Skills of a character: {id, label}. Unit skills up to its star, then skills from equipped materia. */
    static List<String[]> forUnit(String base, int star, List<String> materiaIds) {
        List<String[]> out = new ArrayList<String[]>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        Map<String, Object> u = bundle == null ? null : (Map<String, Object>) bundle.get("u");
        Object l = u == null ? null : u.get(base);
        if (l instanceof List) for (Object o : (List<Object>) l) {
            List<Object> s = (List<Object>) o;
            int t = (int) Editor.num(s.get(0));
            String id = Editor.str(s.get(1));
            if (Editor.num(s.get(2)) > star || t > 1 || !active(t, id) || !seen.add(id)) continue;
            String nm = (t == 0 ? aNames : mNames).get(id);
            out.add(new String[]{id, nm == null ? (t == 0 ? "Ability " : "Magic ") + id : nm});
        }
        Map<String, Object> mm = bundle == null ? null : (Map<String, Object>) bundle.get("m");
        if (mm != null && materiaIds != null) for (String mid : materiaIds) {
            Object e = mm.get(mid);
            if (!(e instanceof List)) continue;
            List<Object> el = (List<Object>) e;
            for (Object sid : (List<Object>) el.get(1)) {
                String id = Editor.str(sid);
                if (!active(id.length() == 5 ? 1 : 0, id) || !seen.add(id)) continue;
                String nm = aNames.get(id);
                if (nm == null) nm = mNames.get(id);
                out.add(new String[]{id, (nm == null ? Editor.str(el.get(0)) : nm) + " (materia)"});
            }
        }
        return out;
    }

    /** One saved party the unit is in: its name ("Party 3"), the esper it carries there (or null) and the materia it wears there. */
    static final class Party {
        String name = "", esper = null;
        List<String> materia = new ArrayList<String>();
    }

    /** The parties (saved party loadouts) a unit with this 8-digit base id is in. Parties where the unit wears the same materia and esper are merged
     *  (names joined). When the save has no party register, one unnamed party with the unit's current materia is returned. */
    static List<Party> partiesOf(Map<String, Object> save, String base, List<String> rosterMateria) {
        List<Party> out = new ArrayList<Party>();
        try {
            Object rg = save.get("exvius_party_register");
            Object slots = rg instanceof Map ? ((Map<String, Object>) rg).get("slots") : null;
            if (slots instanceof Map && base != null) {
                java.util.Map<String, String> inst2base = new java.util.HashMap<String, String>();
                for (Object ro : (List<Object>) save.get("roster")) {
                    Map<String, Object> r = (Map<String, Object>) ro;
                    String mid = Editor.str(r.get("master_id"));
                    if (mid.length() >= 8) inst2base.put(Editor.str(r.get("instance_id")), mid.substring(0, 8));
                }
                List<String> keys = new ArrayList<String>(((Map<String, Object>) slots).keySet());
                java.util.Collections.sort(keys);
                for (String key : keys) {
                    Object so = ((Map<String, Object>) slots).get(key);
                    if (!(so instanceof Map)) continue;
                    Map<String, Object> sl = (Map<String, Object>) so;
                    String mem = Editor.str(sl.get("2A6fYiEC"));
                    Object eo = sl.get("espers"), lo = sl.get("loadouts");
                    for (String part : mem.split(",")) {
                        String[] f = part.split(":");
                        if (f.length < 3 || !base.equals(inst2base.get(f[2]))) continue;
                        Party p = new Party();
                        String nm = Editor.str(sl.get("G4L0YIB2"));
                        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("^SLOT(\\d+)$").matcher(nm);
                        p.name = mt.matches() ? "Party " + mt.group(1) : nm;
                        int ord = 0;
                        try { ord = Integer.parseInt(f[1]); } catch (Exception e) { }
                        if (eo instanceof List && ord < ((List<Object>) eo).size()) {
                            String e = Editor.str(((List<Object>) eo).get(ord));
                            if (!e.isEmpty() && !e.equals("0")) p.esper = e;
                        }
                        if (lo instanceof Map && ((Map<String, Object>) lo).get(f[2]) instanceof Map) {
                            Object mo = ((Map<String, Object>) ((Map<String, Object>) lo).get(f[2])).get("materia");
                            if (mo instanceof List) for (Object m : (List<Object>) mo) p.materia.add(Editor.str(m));
                        }
                        Party same = null;
                        for (Party q : out) if (q.materia.equals(p.materia) && (q.esper == null ? p.esper == null : q.esper.equals(p.esper))) same = q;
                        if (same != null) { if (!same.name.contains(p.name)) same.name = same.name + ", " + p.name; }
                        else out.add(p);
                    }
                }
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) {
            Party p = new Party();
            if (rosterMateria != null) p.materia.addAll(rosterMateria);
            out.add(p);
        }
        return out;
    }

    /** Castable abilities from worn materia: {id, "Name (materia)", name}. */
    static List<String[]> materiaSkills(List<String> materiaIds) {
        List<String[]> out = new ArrayList<String[]>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        Map<String, Object> mm = bundle == null ? null : (Map<String, Object>) bundle.get("m");
        if (mm != null && materiaIds != null) for (String mid : materiaIds) {
            Object e = mm.get(mid);
            if (!(e instanceof List)) continue;
            List<Object> el = (List<Object>) e;
            for (Object sid : (List<Object>) el.get(1)) {
                String id = Editor.str(sid);
                if (!active(id.length() == 5 ? 1 : 0, id) || !seen.add(id)) continue;
                String nm = aNames.get(id);
                if (nm == null) nm = mNames.get(id);
                if (nm == null) nm = Editor.str(el.get(0));
                out.add(new String[]{id, nm + " (materia)", nm});
            }
        }
        return out;
    }

    /** Skills from the player's espers: each esper's summon (by star level) and every spell and ability on its board.
     *  Items are {action, label, id, name}; action is "esper:<skill id>" for a summon, else "skill:<id>". The label names the esper the skill comes from;
     *  Only learned nodes are listed. For a unit in saved party decks only the espers it carries there are used (all of them, if the decks differ); for others every owned esper (the game skips a rule
     *  whose skill the unit cannot cast now, and the next rule is tried). */
    static List<String[]> esperSkills(Map<String, Object> save, java.util.Set<String> only) {
        List<String[]> out = new ArrayList<String[]>();
        if (save == null || eBoard.isEmpty()) return out;
        Object bl = save.get("beasts");
        if (!(bl instanceof List)) return out;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Object bo : (List<Object>) bl) {
            Map<String, Object> b = (Map<String, Object>) bo;
            String eid = Editor.str(b.get("esper_id"));
            if (eid.isEmpty() || eid.equals("0")) continue;
            if (only != null && !only.contains(eid)) continue;
            String en = Tables.names.get(eid);
            if (en == null || en.isEmpty()) en = "esper " + eid;
            String sum = eSummon.get(eid + ":" + Editor.str(b.get("rarity")));
            if (sum != null && eNames.get(sum) != null && seen.add("e" + sum)) out.add(new String[]{"esper:" + sum, eNames.get(sum) + " (" + en + " summon)", sum, eNames.get(sum)});
            String hex = Editor.str(b.get("board"));
            List<Object> nodes = eBoard.get(eid);
            if (nodes == null) continue;
            java.math.BigInteger bits = java.math.BigInteger.ZERO;
            try { if (!hex.isEmpty()) bits = new java.math.BigInteger(hex, 16); } catch (Exception e) { }
            int len = hex.length() * 4;
            for (Object no : nodes) {
                List<Object> n = (List<Object>) no;
                int idx = (int) Editor.num(n.get(0)), ty = (int) Editor.num(n.get(1));
                boolean learned = idx < len && bits.testBit(len - 1 - idx);
                if (!learned) continue;   // only abilities the esper has learned
                String id = Editor.str(n.get(2));
                int t = ty == 20 ? 1 : 0;
                if (!active(t, id) || !seen.add(id)) continue;
                String nm = (t == 1 ? mNames : aNames).get(id);
                if (nm == null) nm = (t == 1 ? aNames : mNames).get(id);
                if (nm == null) continue;
                out.add(new String[]{"skill:" + id, nm + " (" + en + ")", id, nm});
            }
        }
        return out;
    }
}
