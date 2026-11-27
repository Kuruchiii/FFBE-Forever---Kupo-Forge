package com.ffbeforever.saveedit;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Save sync through a folder that a cloud app (Drive, Dropbox, Syncthing...) already syncs. Manual push and pull. */
final class Sync {
    static final String LATEST = "ffbe_save_latest.json";   // the first save slot (campaign); other slots: ffbe_save_<slot>.json

    /** Cloud file name for a save slot. The first slot keeps the old name. */
    static String fileFor(String slot) { return slot == null || slot.equals("campaign") ? LATEST : "ffbe_save_" + slot + ".json"; }
    /** The slot a cloud file name stands for ("campaign" for the first), or null if it is not a slot file. */
    static String slotOf(String name) {
        if (LATEST.equals(name)) return "campaign";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^ffbe_save_(save-[0-9]+)\\.json$").matcher(String.valueOf(name));
        return m.matches() ? m.group(1) : null;
    }
    /** The name inside a save file: its save label, else the player name. "" if none. Looks at the first and last 200 KB only. */
    static String nameOf(byte[] b) {
        try {
            int n = Math.min(b.length, 200000);
            String head = new String(b, 0, n, "UTF-8"), tail = b.length > n ? new String(b, b.length - n, n, "UTF-8") : "";
            java.util.regex.Pattern pl = java.util.regex.Pattern.compile("\"save_label\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""), ph = java.util.regex.Pattern.compile("\"handle_name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            for (java.util.regex.Pattern p : new java.util.regex.Pattern[]{pl, ph}) {
                java.util.regex.Matcher m = p.matcher(head);
                if (!m.find() && tail.length() > 0) { m = p.matcher(tail); if (!m.find()) continue; }
                String r = m.group(1).trim();
                if (r.length() > 0) return r.length() > 40 ? r.substring(0, 40) : r;
            }
        } catch (Exception e) { }
        return "";
    }
    /** Backup file name prefix for a slot (the first slot keeps the old prefix). */
    static String backPre(String slot) { return slot == null || slot.equals("campaign") ? "ffbe_save_" : "ffbe_" + slot + "_"; }

    static final class Item {
        Uri uri;
        String name;
        long time;
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("editor", Context.MODE_PRIVATE); }

    static Uri tree(Context c) {
        String s = prefs(c).getString("syncTree", null);
        return s == null ? null : Uri.parse(s);
    }

    /** True only when Cloud sync is switched on AND a folder is chosen: then logs go to the folder; otherwise the save dialog is used. */
    static boolean useFolder(Context c) { return tree(c) != null && (!Flags.EXP || prefs(c).getBoolean("patchCloud", false)); }

    /** Short folder name for messages, e.g. "Drive/ffbe". */
    static String folderName(Context c) {
        try {
            Uri t = tree(c);
            if (t == null) return "";
            String id = android.provider.DocumentsContract.getTreeDocumentId(t);
            int i = id.indexOf(':');
            String n = i >= 0 ? id.substring(i + 1) : id;
            return n.isEmpty() ? "the sync folder" : n;
        } catch (Exception e) { return "the sync folder"; }
    }

    /** Short device name for file names, e.g. "SM-X900". */
    static String deviceTag() {
        String t = android.os.Build.MODEL == null ? "device" : android.os.Build.MODEL.replaceAll("[^A-Za-z0-9-]", "");
        return t.isEmpty() ? "device" : (t.length() > 16 ? t.substring(0, 16) : t);
    }

    static void setTree(Context c, Uri t) {
        SharedPreferences.Editor e = prefs(c).edit().putString("syncTree", t.toString()).remove("syncBase").remove("syncDecl").remove("syncUpNo");
        for (String key : new java.util.ArrayList<String>(prefs(c).getAll().keySet())) if (key.contains("@") && key.startsWith("sync")) e.remove(key);
        e.apply();
    }

    /** When this phone last uploaded to or loaded from the cloud folder (auto sync), 0 if never. */
    static long last(Context c) { return prefs(c).getLong("syncLast", 0); }

    static String when(long t) {
        return t <= 0 ? "never" : new SimpleDateFormat("d MMM yyyy HH:mm", Locale.US).format(new Date(t));
    }

    private static Uri rootDoc(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    /** Files and folders directly inside a folder (document uri). */
    private static List<Object[]> kids(Context c, Uri tree, Uri parentDoc) throws Exception {
        String pid = DocumentsContract.getDocumentId(parentDoc);
        Uri q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, pid);
        List<Object[]> out = new ArrayList<Object[]>();
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(q, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            while (cur != null && cur.moveToNext()) {
                out.add(new Object[]{DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0)), cur.getString(1),
                        cur.getLong(2), DocumentsContract.Document.MIME_TYPE_DIR.equals(cur.getString(3))});
            }
        } finally {
            if (cur != null) cur.close();
        }
        return out;
    }

    /** The json saves in the sync folder (not the backups folder). */
    static List<Item> saves(Context c, Uri tree) throws Exception {
        List<Item> out = new ArrayList<Item>();
        for (Object[] k : kids(c, tree, rootDoc(tree))) {
            String n = String.valueOf(k[1]);
            if ((Boolean) k[3] || !n.toLowerCase(Locale.ROOT).endsWith(".json")) continue;
            Item it = new Item();
            it.uri = (Uri) k[0];
            it.name = n;
            it.time = (Long) k[2];
            out.add(it);
        }
        return out;
    }

    static Item newest(Context c, Uri tree) throws Exception {
        Item best = null;
        for (Item it : saves(c, tree)) if (best == null || it.time > best.time) best = it;
        return best;
    }

    /** The cloud save file itself, or null. */
    static Item find(Context c, Uri tree) throws Exception { return find(c, tree, null); }

    static Item find(Context c, Uri tree, String slot) throws Exception {
        String f = fileFor(slot);
        for (Item it : saves(c, tree)) if (f.equals(it.name)) return it;
        return null;
    }

    /** Same hash as the game side: FNV-1a 32 over the bytes, then _length. */
    static String hash(byte[] b) {
        int h = 0x811c9dc5;
        for (int i = 0; i < b.length; i++) { h ^= (b[i] & 0xff); h *= 16777619; }
        return Integer.toHexString(h) + "_" + b.length;
    }

    /** Looks like a complete json object, not a half-synced file. */
    static boolean sane(byte[] u) {
        int a = 0, b = u.length - 1;
        while (a < u.length && (u[a] & 0xff) <= 32) a++;
        while (b > 0 && (u[b] & 0xff) <= 32) b--;
        return u.length > 100 && u[a] == 0x7b && u[b] == 0x7d;
    }

    static byte[] read(Context c, Uri u) throws Exception {
        InputStream in = c.getContentResolver().openInputStream(u);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    private static void write(Context c, Uri u, byte[] d) throws Exception {
        OutputStream o = c.getContentResolver().openOutputStream(u, "wt");
        o.write(d);
        o.close();
    }

    /** Saves bytes into backups/ with a time stamp, keeps the newest 20 there. Returns the file name. */
    static String backupBytes(Context c, String prefix, byte[] data, long time) throws Exception {
        Uri t = tree(c);
        if (t == null) throw new Exception("No sync folder chosen.");
        Uri back = null;
        for (Object[] k : kids(c, t, rootDoc(t))) if ((Boolean) k[3] && "backups".equals(k[1])) back = (Uri) k[0];
        if (back == null) back = DocumentsContract.createDocument(c.getContentResolver(), rootDoc(t), DocumentsContract.Document.MIME_TYPE_DIR, "backups");
        if (back == null) throw new Exception("Could not make the backups folder. Nothing was changed.");
        String bn = prefix + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(time > 0 ? time : System.currentTimeMillis())) + ".json";
        Uri b = DocumentsContract.createDocument(c.getContentResolver(), back, "application/json", bn);
        if (b == null) throw new Exception("Could not write the backup. Nothing was changed.");
        write(c, b, data);
        try {
            List<Object[]> files = new ArrayList<Object[]>();
            for (Object[] k : kids(c, t, back)) if (!(Boolean) k[3]) files.add(k);
            java.util.Collections.sort(files, new java.util.Comparator<Object[]>() {
                public int compare(Object[] x, Object[] y) { return String.valueOf(y[1]).compareTo(String.valueOf(x[1])); }
            });
            for (int i = 20; i < files.size(); i++) DocumentsContract.deleteDocument(c.getContentResolver(), (Uri) files.get(i)[0]);
        } catch (Exception ignored) { }
        return bn;
    }

    /** Puts a file in the sync folder (replaces one with the same name). Returns the name. Not a save, so the save sync ignores it. */
    /** How many exported log files stay in the sync folder; older ones are deleted. */
    static final int KEEP_LOGS = 3;

    /** Deletes old log files (ffbe_log.txt, ffbe_log_v*.txt) in the sync folder, keeping the newest KEEP_LOGS. Touches nothing else. Returns how many were deleted. */
    static int cleanLogs(Context c) {
        int gone = 0;
        try {
            Uri t = tree(c);
            if (t == null) return 0;
            List<Object[]> logs = new java.util.ArrayList<Object[]>();
            for (Object[] k : kids(c, t, rootDoc(t))) {
                String n = (String) k[1];
                if (!((Boolean) k[3]) && n != null && n.startsWith("ffbe_log") && n.endsWith(".txt")) logs.add(k);
            }
            java.util.Collections.sort(logs, new java.util.Comparator<Object[]>() {
                public int compare(Object[] a, Object[] b) { return Long.compare((Long) b[2], (Long) a[2]); }
            });
            for (int i = KEEP_LOGS; i < logs.size(); i++) {
                try { if (DocumentsContract.deleteDocument(c.getContentResolver(), (Uri) logs.get(i)[0])) gone++; } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return gone;
    }

    static String putFile(Context c, String name, String mime, byte[] data) throws Exception {
        Uri t = tree(c);
        if (t == null) throw new Exception("No sync folder chosen.");
        for (Object[] k : kids(c, t, rootDoc(t))) if (!(Boolean) k[3] && name.equals(k[1])) { write(c, (Uri) k[0], data); return name; }
        Uri n = DocumentsContract.createDocument(c.getContentResolver(), rootDoc(t), mime, name);
        if (n == null) throw new Exception("Could not create " + name + " in the sync folder.");
        write(c, n, data);
        return name;
    }

    /** Writes the new save as the latest. With backup, the old latest is copied into backups/ first. */
    static String push(Context c, byte[] data, boolean backup) throws Exception { return push(c, data, backup, null); }

    static String push(Context c, byte[] data, boolean backup, String slot) throws Exception {
        Uri t = tree(c);
        if (t == null) throw new Exception("No sync folder chosen.");
        Item cur = find(c, t, slot);
        String note = "";
        if (cur != null) {
            if (backup) {
                String bn = backupBytes(c, backPre(slot), read(c, cur.uri), cur.time);
                note = "The old copy was saved in backups/" + bn + ". ";
            }
            write(c, cur.uri, data);
        } else {
            Uri n = DocumentsContract.createDocument(c.getContentResolver(), rootDoc(t), "application/json", fileFor(slot));
            if (n == null) throw new Exception("Could not create the file in the sync folder.");
            write(c, n, data);
        }
        return note;
    }
}
