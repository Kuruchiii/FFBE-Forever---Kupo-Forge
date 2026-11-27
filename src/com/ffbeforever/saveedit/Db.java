package com.ffbeforever.saveedit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Unit and item names from assets/db.json (built from the public GL datamine). */
public final class Db {
    public static final class Star {
        public final int star; public final String id; public final int lbMax; public final int expPattern;
        Star(int star, String id, int lbMax, int expPattern) {
            this.star = star; this.id = id; this.lbMax = lbMax; this.expPattern = expPattern;
        }
    }
    public static final class Unit {
        public String base, name;
        public int tmrKind; public long tmrId;
        /** Index into banners of the unit's first banner, -1 when it was never on one. */
        public int banner = -1;
        /** True when the banner is estimated from release order, not taken from the game's data. */
        public boolean est;
        public final TreeMap<Integer, Star> stars = new TreeMap<Integer, Star>();
        public int minStar() { return stars.firstKey(); }
        public int maxStar() { return stars.lastKey(); }
    }
    public static final class Item {
        public final int kind; public final long id; public final String name; public final boolean tmr;
        /** Equipment slot (Weapon, Shield, Headgear, Chest, Accessory) or "Materia"; type e.g. Sword. */
        public String slot = "", type = "";
        /** Index into banners of the owner unit's first banner, -1 when not tied to a banner. */
        public int banner = -1;
        public boolean est;
        /** Icon file name in the game's art (e.g. item_10100.png), or "". */
        public String icon = "";
        Item(int kind, long id, String name, boolean tmr) { this.kind = kind; this.id = id; this.name = name; this.tmr = tmr; }
    }
    public static final class Banner {
        public final String date, name; public final int season;
        Banner(String date, String name, int season) { this.date = date; this.name = name; this.season = season; }
        public String label() { return "S" + season + "  " + date + "  " + name; }
    }
    public final List<Unit> units = new ArrayList<Unit>();
    public final List<Banner> banners = new ArrayList<Banner>();
    private final java.util.Set<Long> estIds = new java.util.HashSet<Long>();
    private final Map<Long, Integer> ownerBanner = new HashMap<Long, Integer>();

    public final Map<String, Unit> byMaster = new HashMap<String, Unit>();
    public final Map<String, Star> starOf = new HashMap<String, Star>();
    public final Map<Long, String> tmrOwner = new HashMap<Long, String>();
    public final Map<Long, String> stmrOwner = new HashMap<Long, String>();
    public final List<Item> items = new ArrayList<Item>();
    public final Map<String, String> itemName = new HashMap<String, String>();

    @SuppressWarnings("unchecked")
    public Db(String text) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(text);
        for (Object o : (List<Object>) root.get("units")) {
            Map<String, Object> m = (Map<String, Object>) o;
            Unit u = new Unit();
            units.add(u);
            u.base = (String) m.get("b");
            u.name = (String) m.get("n");
            List<Object> t = (List<Object>) m.get("t");
            u.tmrKind = (int) ((Json.Num) t.get(0)).asLong();
            u.tmrId = ((Json.Num) t.get(1)).asLong();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("s")).entrySet()) {
                List<Object> v = (List<Object>) e.getValue();
                int st = Integer.parseInt(e.getKey());
                Star s = new Star(st, (String) v.get(0), (int) ((Json.Num) v.get(1)).asLong(),
                        (int) ((Json.Num) v.get(2)).asLong());
                u.stars.put(st, s);
                byMaster.put(s.id, u);
                starOf.put(s.id, s);
            }
            Object fbo = m.get("fb");
            int fb = fbo instanceof Json.Num ? (int) ((Json.Num) fbo).asLong() : -1;
            u.banner = fb;
            u.est = m.get("fe") instanceof Json.Num;
            if (u.tmrKind != 0 && !tmrOwner.containsKey(u.tmrId)) tmrOwner.put(u.tmrId, u.name);
            if (u.tmrKind != 0 && fb >= 0 && !ownerBanner.containsKey(u.tmrId)) { ownerBanner.put(u.tmrId, fb); if (u.est) estIds.add(u.tmrId); }
            Object so = m.get("st");
            if (so instanceof List) {
                long sid = ((Json.Num) ((List<Object>) so).get(1)).asLong();
                if (sid != 0 && !stmrOwner.containsKey(sid)) stmrOwner.put(sid, u.name);
                if (sid != 0 && fb >= 0 && !ownerBanner.containsKey(sid)) { ownerBanner.put(sid, fb); if (u.est) estIds.add(sid); }
            }
        }
        Object bl = root.get("banners");
        if (bl instanceof List)
            for (Object o : (List<Object>) bl) {
                List<Object> b = (List<Object>) o;
                banners.add(new Banner((String) b.get(0), String.valueOf(b.get(1)), (int) ((Json.Num) b.get(2)).asLong()));
            }
        addItems((Map<String, Object>) root.get("eq"), 21, null);
        addItems((Map<String, Object>) root.get("ma"), 22, (Map<String, Object>) root.get("mi"));
    }

    @SuppressWarnings("unchecked")
    private void addItems(Map<String, Object> m, int kind, Map<String, Object> icons) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            long id = Long.parseLong(e.getKey());
            String owner = tmrOwner.get(id), sowner = stmrOwner.get(id);
            String name, slot = "Materia", type = "", icon = "";
            Object v = e.getValue();
            if (v instanceof List) {
                List<Object> l = (List<Object>) v;
                name = String.valueOf(l.get(0));
                slot = String.valueOf(l.get(1));
                type = String.valueOf(l.get(2));
                if (l.size() > 3) icon = String.valueOf(l.get(3));
            } else {
                name = String.valueOf(v);
            }
            String shown = name;
            if (owner != null) shown += "  (TMR: " + owner + ")";
            if (sowner != null) shown += "  (STMR: " + sowner + ")";
            Item it = new Item(kind, id, shown, owner != null || sowner != null);
            it.slot = slot;
            it.type = type;
            if (icons != null && icons.get(e.getKey()) != null) icon = String.valueOf(icons.get(e.getKey()));
            it.icon = icon;
            Integer b = ownerBanner.get(id);
            if (b != null && b < banners.size()) { it.banner = b; it.est = estIds.contains(id); }
            items.add(it);
            itemName.put(kind + ":" + id, name);
        }
    }
}
