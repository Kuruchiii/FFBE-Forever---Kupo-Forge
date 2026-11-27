package com.ffbeforever.saveedit;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Story and esper tables, read once from the running game (command tbl) and kept in a cache file. */
@SuppressWarnings("unchecked")
final class Tables {
    static final class Mission {
        String id;
        List<String[]> sw = new ArrayList<String[]>();   // {type, index}
        boolean main;
        String fc = "";   // first-clear reward text from the game (kind:id:count:rate, comma list)
        List<String[]> ch = new ArrayList<String[]>();   // {challenge id, reward csv}
        String dungeon() { return id.substring(0, 5); }
        String number() { return id.substring(5); }
    }

    static final class Beast {
        String id;
        int rar, maxLv;
        String cpKey;
        String[] own, cap;   // switch {type, index} or null
    }

    static final List<Mission> missions = new ArrayList<Mission>();
    static final Map<String, List<Beast>> beasts = new LinkedHashMap<String, List<Beast>>();
    static final Map<String, String> cpAdd = new HashMap<String, String>();
    static final Map<String, String> names = new HashMap<String, String>();
    static final Map<String, String> dungeons = new HashMap<String, String>();

    static final Map<String, String> mNames = new HashMap<String, String>();
    static final Map<String, String> dNames = new HashMap<String, String>();
    static final Map<String, String> aNames = new HashMap<String, String>();

    /** Mission name from the game, or empty. */
    static String mname(Mission m) { String n = mNames.get(m.id); return n == null ? "" : n; }
    /** Place (dungeon) name: game text first, then the app's own list. */
    static String place(Mission m) { String n = dNames.get(m.dungeon()); return n != null ? n : ver(m.dungeon()); }
    /** Region (area) name: game text, else "Area 113". */
    static String region(Mission m) { String a = m.id.substring(0, 3), n = aNames.get(a); return n != null ? n : "Area " + a; }

    static String dbg = "";

    static boolean loaded() { return !missions.isEmpty(); }

    private static File cache(Context c) { return new File(c.getFilesDir(), "tables_cache.json"); }

    /** Reads the cache file, if there is one. */
    static boolean loadCache(Context c) {
        try {
            File f = cache(c);
            if (!f.exists()) return false;
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int got = 0;
            while (got < b.length) { int r = in.read(b, got, b.length - got); if (r <= 0) break; got += r; }
            in.close();
            parse(new String(b, 0, got, "UTF-8"));
            return loaded();
        } catch (Exception e) {
            return false;
        }
    }

    /** Asks the running game for the tables. Takes up to a minute. */
    static void fetch(Context c) throws Exception {
        String r = Speed.sendBig("tbl", 240000);
        if (!r.startsWith("T ")) throw new Exception("The game did not send its tables. Patch the game first, then open it.");
        String json = r.substring(2);
        parse(json);
        if (!loaded()) throw new Exception("The game's tables had no story missions.");
        FileOutputStream o = new FileOutputStream(cache(c));
        o.write(json.getBytes("UTF-8"));
        o.close();
    }

    static String ver(String dungeonId) {
        String n = dungeons.get(dungeonId);
        return n == null ? "" : n;
    }

    private static void parse(String json) throws Exception {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new Exception("Bad table data");
        Map<String, Object> m = (Map<String, Object>) root;
        missions.clear(); beasts.clear(); cpAdd.clear(); names.clear();
        Map<String, Object> ch = (Map<String, Object>) m.get("c");
        for (Object o : (List<Object>) m.get("m")) {
            List<Object> a = (List<Object>) o;
            Mission x = new Mission();
            x.id = Editor.str(a.get(0));
            for (Object p : (List<Object>) a.get(1)) {
                List<Object> pp = (List<Object>) p;
                x.sw.add(new String[]{Editor.str(pp.get(0)), Editor.str(pp.get(1))});
            }
            x.main = Editor.num(a.get(2)) == 1;
            if (a.size() > 3) x.fc = Editor.str(a.get(3));
            Object cl = ch == null ? null : ch.get(x.id);
            if (cl instanceof List) for (Object q : (List<Object>) cl) {
                List<Object> qq = (List<Object>) q;
                x.ch.add(new String[]{Editor.str(qq.get(0)), Editor.str(qq.get(1))});
            }
            missions.add(x);
        }
        java.util.Collections.sort(missions, new java.util.Comparator<Mission>() {
            public int compare(Mission p, Mission q) { return p.id.compareTo(q.id); }
        });
        for (Object o : (List<Object>) m.get("b")) {
            List<Object> a = (List<Object>) o;
            Beast b = new Beast();
            b.id = Editor.str(a.get(0));
            b.rar = (int) Editor.num(a.get(1));
            b.maxLv = (int) Editor.num(a.get(2));
            b.cpKey = Editor.str(a.get(3));
            b.own = pos(a.get(4));
            b.cap = pos(a.get(5));
            List<Beast> l = beasts.get(b.id);
            if (l == null) { l = new ArrayList<Beast>(); beasts.put(b.id, l); }
            l.add(b);
        }
        for (List<Beast> l : beasts.values()) java.util.Collections.sort(l, new java.util.Comparator<Beast>() {
            public int compare(Beast p, Beast q) { return p.rar - q.rar; }
        });
        Map<String, Object> cp = (Map<String, Object>) m.get("cp");
        if (cp != null) for (Map.Entry<String, Object> e : cp.entrySet()) cpAdd.put(e.getKey(), Editor.str(e.getValue()));
        mNames.clear(); dNames.clear(); aNames.clear();
        fillNames(mNames, m.get("mn")); fillNames(dNames, m.get("dn")); fillNames(aNames, m.get("an"));
        dbg = m.get("dbg") == null ? "" : m.get("dbg").toString();
        Map<String, Object> n = (Map<String, Object>) m.get("n");
        if (n != null) for (Map.Entry<String, Object> e : n.entrySet()) names.put(e.getKey(), Editor.str(e.getValue()));
    }

    private static void fillNames(Map<String, String> to, Object o) {
        if (o instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) to.put(e.getKey(), Editor.str(e.getValue()));
    }

    private static String[] pos(Object o) {
        if (!(o instanceof List)) return null;
        List<Object> l = (List<Object>) o;
        return new String[]{Editor.str(l.get(0)), Editor.str(l.get(1))};
    }

    /** Dungeon names for the story areas, from the app's own list. */
    static void loadDungeons(Context c) {
        try {
            java.io.InputStream in = c.getAssets().open("dungeons.json");
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
            in.close();
            Object o = Json.parse(new String(b.toByteArray(), "UTF-8"));
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) dungeons.put(e.getKey(), Editor.str(e.getValue()));
        } catch (Exception ignored) { }
    }
}
