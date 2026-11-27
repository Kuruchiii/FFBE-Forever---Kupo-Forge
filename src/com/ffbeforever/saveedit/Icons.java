package com.ffbeforever.saveedit;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Unit and item icons the player copied out of their own game (see the Game Patch tab). Kept in the app's private storage. */
public final class Icons {
    public interface Progress { void step(String text); }

    private static File dir;
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(600);
    private static final Bitmap NONE = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);

    private Icons() { }

    private static Context app;
    private static byte[] pack;
    private static java.util.HashMap<String, int[]> index;

    public static void init(Context c) { app = c.getApplicationContext(); dir = new File(c.getFilesDir(), "icons"); dir.mkdirs(); }

    /** Icons bundled in the APK (assets/icons.bin: 'FFIC', count, name/offset/size index, WebP bytes). */
    private static synchronized void loadPack() {
        if (index != null) return;
        index = new java.util.HashMap<String, int[]>();
        try {
            InputStream in = app.getAssets().open("icons.bin");
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            in.close();
            pack = bo.toByteArray();
            int n = (pack[4] & 255) | (pack[5] & 255) << 8 | (pack[6] & 255) << 16 | (pack[7] & 255) << 24;
            int p = 8;
            for (int i = 0; i < n; i++) {
                int l = pack[p++] & 255;
                String name = new String(pack, p, l, "UTF-8");
                p += l;
                int off = (pack[p] & 255) | (pack[p + 1] & 255) << 8 | (pack[p + 2] & 255) << 16 | (pack[p + 3] & 255) << 24;
                int len = (pack[p + 4] & 255) | (pack[p + 5] & 255) << 8 | (pack[p + 6] & 255) << 16 | (pack[p + 7] & 255) << 24;
                p += 8;
                index.put(name, new int[]{off, len});
            }
        } catch (Exception e) {
            pack = null;
        }
    }

    public static int count() {
        loadPack();
        String[] n = dir == null ? null : dir.list();
        return index.size() + (n == null ? 0 : n.length);
    }

    public static int bundled() { loadPack(); return index.size(); }

    public static void clear() {
        cache.evictAll();
        String[] n = dir.list();
        if (n != null) for (String s : n) new File(dir, s).delete();
    }

    /** Icon for a unit (its base id; the first 8 digits name the unit family), or null. */
    public static Bitmap unit(String base) {
        return get("u_" + (base.length() > 8 ? base.substring(0, 8) : base));
    }

    /** Icon for an item (equipment or materia id), or null. */
    public static Bitmap item(Db.Item it) {
        if (it.icon == null || it.icon.length() < 5) return null;
        return get(it.icon.substring(0, it.icon.length() - 4));
    }

    /** A skill category icon, ability_N.png (N = 0 is ability_000). */
    public static Bitmap ability(int n) { return get(n == 0 ? "ability_000" : "ability_" + n); }

    /** A picture by its file name without .png (from the game's icon table). */
    public static Bitmap named(String n) { return get(n); }

    private static Bitmap get(String key) {
        if (dir == null) return null;
        Bitmap b = cache.get(key);
        if (b != null) return b == NONE ? null : b;
        File f = new File(dir, key + ".png");
        b = f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
        if (b == null) {
            loadPack();
            int[] e = index.get(key);
            if (e != null && pack != null) b = BitmapFactory.decodeByteArray(pack, e[0], e[1]);
        }
        cache.put(key, b == null ? NONE : b);
        return b;
    }

    /** Reads the export folder (Download/ffbe_icons) chosen by the player. Returns a short report. */
    public static String importTree(Context c, Uri tree, Db db, Progress p) {
        ContentResolver cr = c.getContentResolver();
        String root = DocumentsContract.getTreeDocumentId(tree);
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root);
        int units = 0, items = 0, skipped = 0, seen = 0, other = 0;
        Cursor q = cr.query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        if (q == null) return "Could not read that folder.";
        try {
            while (q.moveToNext()) {
                String id = q.getString(0), name = q.getString(1);
                seen++;
                if (seen % 150 == 0 && p != null) p.step("Importing icons... " + seen + " files looked at");
                String key = null;
                boolean unit = false;
                if (name.startsWith("unit_icon_") && name.endsWith(".png") && name.length() == 23) {
                    key = "u_" + name.substring(10, 18); unit = true;
                } else if (name.endsWith(".png") && (name.startsWith("item_") || name.startsWith("global_equip_")
                        || name.startsWith("global_item_") || name.startsWith("ability_"))) {
                    key = name.substring(0, name.length() - 4);
                }
                if (key == null) { other++; continue; }
                try {
                    InputStream in = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                    in.close();
                    byte[] data = bo.toByteArray();
                    if (!unit) {
                        // Items are 112x112: half size is plenty for a list row.
                        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
                        if (b == null) { skipped++; continue; }
                        Bitmap s = Bitmap.createScaledBitmap(b, 64, 64, true);
                        ByteArrayOutputStream po = new ByteArrayOutputStream();
                        s.compress(Bitmap.CompressFormat.PNG, 100, po);
                        data = po.toByteArray();
                    }
                    FileOutputStream o = new FileOutputStream(new File(dir, key + ".png"));
                    o.write(data);
                    o.close();
                    if (unit) units++; else items++;
                } catch (Exception e) {
                    skipped++;
                }
            }
        } finally {
            q.close();
        }
        cache.evictAll();
        int uHit = 0;
        for (Db.Unit u : db.units) if (get("u_" + (u.base.length() > 8 ? u.base.substring(0, 8) : u.base)) != null) uHit++;
        int iHit = 0;
        for (Db.Item it : db.items) if (item(it) != null) iHit++;
        return "Imported " + units + " unit icons and " + items + " item icons (" + skipped + " could not be read, "
                + other + " other files ignored).\nMatched to this editor's data: " + uHit + " of " + db.units.size() + " units, "
                + iHit + " of " + db.items.size() + " items.";
    }
}
