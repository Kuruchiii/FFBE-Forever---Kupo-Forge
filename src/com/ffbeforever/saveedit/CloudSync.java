package com.ffbeforever.saveedit;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;

/** Auto cloud sync. Runs in the editor app: it reads the game save through the game hook and the cloud folder through the
 *  folder the player chose. The game never touches the cloud folder. Nothing is merged: one whole save wins, the other is backed up. */
final class CloudSync {
    static final int NONE = 0, ASK_UP = 1, ASK_PULL = 2, CONFLICT = 3;

    static final class Result {
        int kind;
        String key;        // same key = same situation, so the player is not asked again
        String cloud;      // cloud hash the question is about
        String error;      // shown once, if not null
        long localTime, cloudTime;   // save times in ms, 0 if unknown
    }

    private static long lastCloudTime;
    private static byte[] lastCb;
    /** The save slot this sync round is about (set by tick). The first slot is "campaign". */
    private static volatile String cur = "campaign";

    /** Preference key for one slot: the first slot keeps the old key, so old settings still work. */
    private static String k(String key) { return cur.equals("campaign") ? key : key + "@" + cur; }

    /** The game's active save slot now, or null if it does not answer. Games without slots answer "campaign". */
    static String activeSlot() {
        try {
            String r = Speed.send("slotact");
            return r != null && r.startsWith("A ") ? r.substring(2).trim() : "campaign";
        } catch (Exception e) { return null; }
    }

    /** " (Save 2)" for the prompts; empty for the first slot. */
    static String slotTag() { return cur.equals("campaign") ? "" : " (" + (curName.length() > 0 ? curName : shortName(cur)) + ")"; }
    private static volatile String curName = "";
    /** Names shown in the sync questions: the phone save and the cloud save (set when a question is asked). */
    static volatile String phoneName = "", cloudName = "";

    private static String slotChanged() {
        String a = activeSlot();
        return a != null && !a.equals(cur) ? "The game switched to another save slot. Nothing was changed; the next check asks again." : null;
    }
    private static String pend;
    private static long pendAt;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("editor", Context.MODE_PRIVATE); }

    private static Result res(int kind, String key, String cloud) {
        Result r = new Result();
        r.kind = kind; r.key = key; r.cloud = cloud;
        if (kind != NONE) {
            r.localTime = localTime(); r.cloudTime = lastCloudTime;
            String pn = "";
            try { for (Slot x : slots()) if (x.id.equals(cur)) pn = x.name; } catch (Exception e) { }
            phoneName = pn.length() > 0 ? pn : (cur.equals("campaign") ? "Original save" : shortName(cur));
            String cn = lastCb == null ? "" : Sync.nameOf(lastCb);
            cloudName = cn.length() > 0 ? cn : "Cloud save";
        }
        return r;
    }

    private static void setBase(Context c, String h) {
        prefs(c).edit().putString(k("syncBase"), h).remove(k("syncDecl")).remove(k("syncUpNo")).putLong(k("syncLast"), System.currentTimeMillis()).apply();
    }

    /** The phone save hash, or null if the game is not ready or is writing. */
    private static String localHash() {
        try {
            String r = Speed.send("shash");
            return r != null && r.startsWith("H ") ? r.substring(2) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** When the game save file was last written, in ms (0 if the patched game cannot tell). */
    private static long localTime() {
        try {
            String r = Speed.send("smtime");
            return r != null && r.startsWith("T ") ? Long.parseLong(r.substring(2).trim()) * 1000L : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Short time for button text: [mm-dd hh:mm], or [--] if unknown. */
    static String stamp(long t) {
        return t <= 0 ? "[--]" : "[" + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(t)) + "]";
    }

    /** Reads the whole phone save through the hook and checks it against its hash. */
    static byte[] fetchLocal() throws Exception {
        String h = localHash();
        if (h == null) throw new Exception("The game did not give its save. Check that the game is open (past the title screen), that Install Script was done after the last game start, and that it is not an old patched copy from before this app version.");
        int len = Integer.parseInt(h.substring(h.indexOf('_') + 1));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int step = 393216, off = 0;
        while (off < len) {
            String d = Speed.sendBig("sget " + off + " " + step, 30000);
            if (!d.startsWith("D ")) throw new Exception("The game could not read its save.");
            out.write(Base64.decode(d.substring(2), Base64.DEFAULT));
            off += step;
        }
        byte[] b = out.toByteArray();
        if (!Sync.hash(b).equals(h)) throw new Exception("The phone save changed while it was read. Try again.");
        return b;
    }

    /** One check. Pushes quietly when only the phone changed; otherwise says what to ask the player. */
    static volatile String lastNote = "";

    static Result tick(Context c) throws Exception {
        Uri t = Sync.tree(c);
        if (t == null) { lastNote = "no sync folder chosen on this device"; return res(NONE, "", null); }
        String sl = activeSlot();
        if (sl == null) { lastNote = "the game did not answer"; return res(NONE, "", null); }
        if (!sl.equals(cur) || (!cur.equals("campaign") && curName.length() == 0)) {
            if (!sl.equals(cur)) { cur = sl; pend = null; }
            curName = "";
            try { for (Slot x : slots()) if (x.id.equals(cur)) curName = x.name; } catch (Exception e) { }
        }
        String local = localHash();
        if (local == null) { lastNote = "the game did not give its save yet (not past the title screen, or writing)"; return res(NONE, "", null); }
        Sync.Item it = Sync.find(c, t, cur);
        byte[] cb = it == null ? null : Sync.read(c, it.uri);
        lastCb = cb;
        if (cb != null && !Sync.sane(cb)) { lastNote = "cloud file is still syncing, waiting"; return res(NONE, "", null); }     // half synced: wait for the next check
        String cloud = cb == null ? null : Sync.hash(cb);
        lastCloudTime = it == null ? 0 : it.time;
        SharedPreferences p = prefs(c);
        String base = p.getString(k("syncBase"), null), decl = p.getString(k("syncDecl"), null);
        if (cloud == null) {
            if (p.getBoolean(k("syncUpNo"), false)) { lastNote = "no cloud save yet (you said No to uploading)"; return res(NONE, "", null); }
            lastNote = "no cloud save yet: asked to upload";
            return res(ASK_UP, "up|" + local, null);
        }
        if (p.getBoolean(k("syncUpNo"), false)) p.edit().remove(k("syncUpNo")).apply();
        if (local.equals(cloud)) {
            if (!cloud.equals(base) || decl != null) setBase(c, cloud);
            pend = null;
            lastNote = "in sync: this device matches the cloud save";
            return res(NONE, "", null);
        }
        if (base == null) { lastNote = "cloud and this device differ: asked which to keep"; return res(CONFLICT, local + "|" + cloud, cloud); }
        boolean lc = !local.equals(base), cc = !cloud.equals(base);
        if (!lc && cc) { lastNote = cloud.equals(decl) ? "newer cloud save found, you said No earlier" : "newer cloud save found: asked to load it"; return cloud.equals(decl) ? res(NONE, "", null) : res(ASK_PULL, "pull|" + cloud, cloud); }
        if (lc && cc && !cloud.equals(decl)) { lastNote = "both changed: asked which to keep"; return res(CONFLICT, local + "|" + cloud, cloud); }
        if (!lc) { lastNote = "nothing new"; return res(NONE, "", null); }
        // Only the phone changed (or the player already said No to that cloud file): upload when it has settled.
        if (!local.equals(pend)) { lastNote = "this device changed: waiting for it to settle"; pend = local; pendAt = System.currentTimeMillis(); return res(NONE, "", null); }
        if (System.currentTimeMillis() - p.getLong(k("syncLast"), 0) < 120000) { lastNote = "this device changed: uploading soon"; return res(NONE, "", null); }
        String e = upload(c, !cloud.equals(base), local);
        lastNote = e == null ? "this device changed: uploaded to the cloud" : "upload failed";
        Result r = res(NONE, "", null);
        r.error = e;
        return r;
    }

    /** Uploads the phone save. expect: its hash, or null for any. Returns an error text or null. */
    static String upload(Context c, boolean backup, String expect) {
        try {
            String ch0 = slotChanged();
            if (ch0 != null) return ch0;
            byte[] b = fetchLocal();
            if (expect != null && !Sync.hash(b).equals(expect)) return null;       // changed meanwhile: next check
            Sync.push(c, b, backup, cur);
            setBase(c, Sync.hash(b));
            pend = null;
            return null;
        } catch (Exception e) {
            return "Cloud sync: " + e.getMessage();
        }
    }

    static void decline(Context c, String cloud) {
        if (cloud == null) prefs(c).edit().putBoolean(k("syncUpNo"), true).apply();
        else prefs(c).edit().putString(k("syncDecl"), cloud).apply();
    }

    /** Loads the cloud save into the game. Backs the phone save up first. Returns null when the game was told to restart, else an error. */
    static String download(Context c, String expect) {
        try {
            Uri t = Sync.tree(c);
            if (t == null) return "No sync folder chosen.";
            String ch0 = slotChanged();
            if (ch0 != null) return ch0;
            Sync.Item it = Sync.find(c, t, cur);
            if (it == null) return "The cloud save is gone.";
            byte[] cb = Sync.read(c, it.uri);
            if (!Sync.sane(cb)) return "The cloud file looks unfinished. Try again in a minute.";
            String ch = Sync.hash(cb);
            if (expect != null && !ch.equals(expect)) return "The cloud file changed. Try again.";
            byte[] lb = fetchLocal();
            Sync.backupBytes(c, cur.equals("campaign") ? "ffbe_phone_" : "ffbe_phone_" + cur + "_", lb, System.currentTimeMillis());
            String r = sendToGame(cb);
            if (r != null) return r;
            setBase(c, ch);
            pend = null;
            return null;
        } catch (Exception e) {
            return "Cloud sync: " + e.getMessage();
        }
    }

    /** Hands a whole save to the game. It checks it, keeps its old save, writes the new one and restarts. Returns an error text or null. */
    static String sendToGame(byte[] cb) throws Exception {
        String ch = Sync.hash(cb);
        int step = 196608, total = cb.length, idx = 0;
        for (int off = 0; off < total; off += step, idx++) {
            int n = Math.min(step, total - off);
            String r = Speed.sendBig("sput " + idx + " " + total + " " + Base64.encodeToString(cb, off, n, Base64.NO_WRAP), 30000);
            if (!r.startsWith("ok")) return "The game refused the data (" + r + ").";
        }
        String r = Speed.sendBig("sapply " + ch, 30000);
        if (!r.startsWith("restart")) return "The game did not take the save (" + r + "). Nothing was changed.";
        return null;
    }

    /** The phone save hash now, or null. */
    static String hashNow() { return localHash(); }

    /** Name for a slot with no label: "Save ..." and the last 4 digits of its number. */
    static String shortName(String id) {
        String n = id.substring(id.indexOf('-') + 1);
        return "Save ..." + (n.length() > 4 ? n.substring(n.length() - 4) : n);
    }

    /** One save slot of the game (game 0.1.19+). */
    static final class Slot { String id, name; long time, bytes; boolean active, missing; Object tag; }

    /** The game's save slots, the active one first, then newest first. Empty if the game does not know slots. */
    static java.util.List<Slot> slots() throws Exception {
        java.util.List<Slot> out = new java.util.ArrayList<Slot>();
        String r = Speed.sendBig("slots", 20000);
        if (r == null || !r.startsWith("S ")) return out;
        String[] p = r.substring(2).split(";");
        String act = p.length > 0 ? p[0] : "campaign";
        for (int i = 1; i < p.length; i++) {
            String[] f = p[i].split("\\|", -1);
            if (f.length < 3) continue;
            Slot s = new Slot();
            s.id = f[0];
            try { s.time = Long.parseLong(f[1]) * 1000L; s.bytes = Long.parseLong(f[2]); } catch (Exception e) { }
            s.name = f.length > 3 ? f[3].trim() : "";
            if (s.name.length() == 0) s.name = s.id.equals("campaign") ? "Original save" : shortName(s.id);
            s.active = s.id.equals(act);
            out.add(s);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Slot>() {
            public int compare(Slot a, Slot b) {
                if (a.active != b.active) return a.active ? -1 : 1;
                return a.time == b.time ? 0 : (a.time > b.time ? -1 : 1);
            }
        });
        return out;
    }

    /** Creates a new save slot on this phone from a cloud save. Never replaces a slot, never switches to it. Returns an error text or null. */
    static String createSlot(byte[] cb, String id) throws Exception {
        String ch = Sync.hash(cb);
        int step = 196608, total = cb.length, idx = 0;
        for (int off = 0; off < total; off += step, idx++) {
            int n = Math.min(step, total - off);
            String r = Speed.sendBig("sput " + idx + " " + total + " " + Base64.encodeToString(cb, off, n, Base64.NO_WRAP), 30000);
            if (!r.startsWith("ok")) return "The game refused the data (" + r + ").";
        }
        String r = Speed.sendBig("slotnew " + id + " " + ch, 30000);
        if (r.startsWith("ok")) return null;
        if (r.startsWith("exists")) return "That slot already exists on this phone.";
        return "The game could not create the slot (" + r + "). Nothing was changed.";
    }

    /** Points the game's save reads and writes at one slot ("-" or null = the active one again). */
    static boolean pick(String id) {
        try { String r = Speed.send("slotpick " + (id == null ? "-" : id)); return r != null && r.startsWith("ok"); } catch (Exception e) { return false; }
    }
}
