package com.ffbeforever.saveedit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Adds the speed hook to the game's Frida script (lib/arm64-v8a/libexviustest.js.so) and re-signs.
 * Every other entry is copied byte for byte (still compressed). The old signature files are dropped;
 * apksig writes a new v1 + v2 signature and re-aligns stored entries.
 */
public final class Patcher {
    public static final String SCRIPT = "lib/arm64-v8a/libexviustest.js.so";
    public static final String MARK = "/*FFBE-SPEED-PATCH v1*/";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public interface Log { void line(String s); }

    /** 1 = portrait only (the game's own setting), 2 = follow the phone's rotation (landscape mode). */
    public static int orientation = 1;

    /** Sets the main activity's screenOrientation and widens configChanges so a turn does not restart it. Returns null if the manifest is not the known shape. */
    static byte[] patchManifest(byte[] d, int orient, Log log) {
        try {
            byte[] o = d.clone();
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(o).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int sp = 8;
            if (b.getShort(sp) != 1) return null;
            int ssz = b.getInt(sp + 4), n = b.getInt(sp + 8), fl = b.getInt(sp + 16), sst = b.getInt(sp + 20);
            if ((fl & 0x100) != 0) return null;            // utf-8 pool: not the shape I know
            String[] str = new String[n];
            for (int i = 0; i < n; i++) {
                int p = sp + sst + b.getInt(sp + 28 + 4 * i);
                int l = b.getShort(p) & 0xffff;
                str[i] = new String(o, p + 2, l * 2, "UTF-16LE");
            }
            int p = sp + ssz, done = 0;
            while (p + 8 <= o.length) {
                int t = b.getShort(p) & 0xffff, sz = b.getInt(p + 4);
                if (t == 0x102) {
                    int nm = b.getInt(p + 20), ao = b.getShort(p + 24) & 0xffff, ac = b.getShort(p + 28) & 0xffff;
                    if ("activity".equals(str[nm])) {
                        int base = p + 16 + ao;
                        boolean main = false;
                        int so = -1, co = -1;
                        for (int i = 0; i < ac; i++) {
                            int q = base + i * 20;
                            String an = str[b.getInt(q + 4)];
                            if ("name".equals(an) && b.getInt(q + 8) >= 0 && b.getInt(q + 8) < n
                                    && "com.square_enix.android_googleplay.FFBEWW.Lapis".equals(str[b.getInt(q + 8)])) main = true;
                            if ("screenOrientation".equals(an)) so = q + 16;
                            if ("configChanges".equals(an)) co = q + 16;
                        }
                        if (main && so > 0 && co > 0) {
                            b.putInt(so, orient);
                            b.putInt(co, b.getInt(co) | 0x80 | 0x400 | 0x800 | 0x100);
                            done++;
                        }
                    }
                }
                if (sz <= 0) break;
                p += sz;
            }
            if (done != 1) return null;
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    static final class Entry {
        String name; byte[] nameBytes; int method, flags, time, date; long crc, csize, usize, lfh;
        int version, extAttr, intAttr; byte[] comment;
        byte[] data;                 // set for an entry that is added (not copied)
    }

    /** Game-side folder-picker patch: replaces the game's classes7.dex (only if it is the known build) and adds classes8.dex. */
    public static final class Extra {
        public byte[] dex7, dex8; public String origSha;
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
    private static long u32(byte[] b, int o) { return u16(b, o) | ((long) u16(b, o + 2)) << 16; }

    private static List<Entry> centralDir(RandomAccessFile f) throws IOException {
        long len = f.length();
        int tail = (int) Math.min(len, 65557);
        byte[] t = new byte[tail];
        f.seek(len - tail);
        f.readFully(t);
        int eocd = -1;
        for (int i = tail - 22; i >= 0; i--)
            if (t[i] == 0x50 && t[i + 1] == 0x4b && t[i + 2] == 5 && t[i + 3] == 6) { eocd = i; break; }
        if (eocd < 0) throw new IOException("Not a zip/APK file");
        int count = u16(t, eocd + 10);
        long cdSize = u32(t, eocd + 12), cdOff = u32(t, eocd + 16);
        if (count == 0xffff || cdOff == 0xffffffffL) throw new IOException("ZIP64 APKs are not supported");
        byte[] cd = new byte[(int) cdSize];
        f.seek(cdOff);
        f.readFully(cd);
        List<Entry> out = new ArrayList<Entry>();
        int p = 0;
        for (int k = 0; k < count; k++) {
            if (u32(cd, p) != 0x02014b50L) throw new IOException("Bad central directory");
            Entry e = new Entry();
            e.version = u16(cd, p + 6);
            e.flags = u16(cd, p + 8);
            e.method = u16(cd, p + 10);
            e.time = u16(cd, p + 12);
            e.date = u16(cd, p + 14);
            e.crc = u32(cd, p + 16);
            e.csize = u32(cd, p + 20);
            e.usize = u32(cd, p + 24);
            int nl = u16(cd, p + 28), xl = u16(cd, p + 30), cl = u16(cd, p + 32);
            e.intAttr = u16(cd, p + 36);
            e.extAttr = (int) u32(cd, p + 38);
            e.lfh = u32(cd, p + 42);
            e.nameBytes = new byte[nl];
            System.arraycopy(cd, p + 46, e.nameBytes, 0, nl);
            e.name = new String(e.nameBytes, UTF8);
            e.comment = new byte[cl];
            System.arraycopy(cd, p + 46 + nl + xl, e.comment, 0, cl);
            p += 46 + nl + xl + cl;
            out.add(e);
        }
        return out;
    }

    private static long dataStart(RandomAccessFile f, Entry e) throws IOException {
        byte[] h = new byte[30];
        f.seek(e.lfh);
        f.readFully(h);
        if (u32(h, 0) != 0x04034b50L) throw new IOException("Bad local header: " + e.name);
        return e.lfh + 30 + u16(h, 26) + u16(h, 28);
    }

    /** Stored native libs are mapped straight from the APK: 16 KB alignment covers 4 KB and 16 KB page devices. */
    static int pageAlign(String n) {
        return n.startsWith("lib/") && n.endsWith(".so") ? 16384 : 4;
    }

    static boolean isOldSignature(String n) {
        if (!n.startsWith("META-INF/") || n.indexOf('/', 9) >= 0) return false;
        String u = n.toUpperCase(java.util.Locale.ROOT);
        return u.equals("META-INF/MANIFEST.MF") || u.endsWith(".SF") || u.endsWith(".RSA")
                || u.endsWith(".DSA") || u.endsWith(".EC");
    }

    private static byte[] read(RandomAccessFile f, long off, long n) throws IOException {
        byte[] b = new byte[(int) n];
        f.seek(off);
        f.readFully(b);
        return b;
    }

    private static byte[] inflate(byte[] data, long size) throws IOException {
        Inflater inf = new Inflater(true);
        inf.setInput(data);
        byte[] out = new byte[(int) size];
        try {
            int n = 0;
            while (n < out.length) {
                int r = inf.inflate(out, n, out.length - n);
                if (r == 0 && (inf.finished() || inf.needsInput())) break;
                n += r;
            }
            if (n != out.length) throw new IOException("Script entry is damaged");
        } catch (java.util.zip.DataFormatException ex) {
            throw new IOException("Script entry is damaged");
        } finally {
            inf.end();
        }
        return out;
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(9, true);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream(data.length / 3);
        byte[] buf = new byte[65536];
        while (!d.finished()) o.write(buf, 0, d.deflate(buf));
        d.end();
        return o.toByteArray();
    }

    /**
     * Returns the script with the hook added. The file is a Frida bundle: "📦\n<size> <name>\n...✄\n<code>"
     * (modules separated by "\n✄\n"). The hook goes at the end of the last module and that module's
     * size is updated. A hook from an earlier patch is replaced.
     */
    static byte[] patchScript(byte[] src, byte[] hook) throws IOException {
        String s = new String(src, UTF8);
        if (!s.startsWith("📦\n")) {
            // Plain script.
            int m = s.indexOf("\n" + MARK);
            if (m >= 0) s = s.substring(0, m);
            return (s + new String(hook, UTF8)).getBytes(UTF8);
        }
        int hdrEnd = s.indexOf("✄\n");
        if (hdrEnd < 0) throw new IOException("Unknown script bundle layout");
        String header = s.substring(0, hdrEnd);
        String body = s.substring(hdrEnd + 2);
        String[] lines = header.split("\n");
        List<long[]> sizes = new ArrayList<long[]>();
        List<String> names = new ArrayList<String>();
        for (int i = 1; i < lines.length; i++) {
            String ln = lines[i];
            int sp = ln.indexOf(' ');
            if (sp <= 0) throw new IOException("Unknown script bundle header");
            sizes.add(new long[]{Long.parseLong(ln.substring(0, sp))});
            names.add(ln.substring(sp + 1));
        }
        if (sizes.isEmpty()) throw new IOException("Empty script bundle");
        // Split the body into modules by their byte sizes.
        byte[] b = body.getBytes(UTF8);
        List<byte[]> mods = new ArrayList<byte[]>();
        int pos = 0;
        byte[] sep = "\n✄\n".getBytes(UTF8);
        for (int i = 0; i < sizes.size(); i++) {
            int n = (int) sizes.get(i)[0];
            if (pos + n > b.length) throw new IOException("Script bundle sizes do not match");
            byte[] m = new byte[n];
            System.arraycopy(b, pos, m, 0, n);
            mods.add(m);
            pos += n;
            if (i < sizes.size() - 1) {
                for (int k = 0; k < sep.length; k++)
                    if (pos + k >= b.length || b[pos + k] != sep[k]) throw new IOException("Script bundle separator missing");
                pos += sep.length;
            }
        }
        if (pos != b.length) throw new IOException("Script bundle has trailing data");
        int last = mods.size() - 1;
        String code = new String(mods.get(last), UTF8);
        int m = code.indexOf("\n" + MARK);
        if (m >= 0) code = code.substring(0, m);
        byte[] nc = (code + new String(hook, UTF8)).getBytes(UTF8);
        mods.set(last, nc);
        ByteArrayOutputStream o = new ByteArrayOutputStream(src.length + hook.length + 64);
        StringBuilder h = new StringBuilder("📦\n");
        for (int i = 0; i < mods.size(); i++) h.append(mods.get(i).length).append(' ').append(names.get(i)).append('\n');
        h.append("✄\n");
        o.write(h.toString().getBytes(UTF8));
        for (int i = 0; i < mods.size(); i++) {
            if (i > 0) o.write(sep);
            o.write(mods.get(i));
        }
        return o.toByteArray();
    }

    public static boolean hasScript(File apk) throws IOException {
        RandomAccessFile f = new RandomAccessFile(apk, "r");
        try {
            for (Entry e : centralDir(f)) if (e.name.equals(SCRIPT)) return true;
            return false;
        } finally {
            f.close();
        }
    }

    /** Reports whether the APK already carries this hook. */
    public static boolean isPatched(File apk) throws IOException {
        RandomAccessFile f = new RandomAccessFile(apk, "r");
        try {
            for (Entry e : centralDir(f)) {
                if (!e.name.equals(SCRIPT)) continue;
                byte[] raw = read(f, dataStart(f, e), e.csize);
                byte[] data = e.method == 0 ? raw : inflate(raw, e.usize);
                return new String(data, UTF8).contains(MARK);
            }
            return false;
        } finally {
            f.close();
        }
    }

    private static void w16(ByteArrayOutputStream o, int v) { o.write(v & 0xff); o.write((v >> 8) & 0xff); }
    private static void w32(ByteArrayOutputStream o, long v) { w16(o, (int) (v & 0xffff)); w16(o, (int) ((v >> 16) & 0xffff)); }

    /** Writes an unsigned copy of src with the hook added. */
    public static void rebuild(File src, File dst, byte[] hook, Log log) throws IOException {
        rebuild(src, dst, hook, null, log);
    }

    private static String sha(byte[] b) throws IOException {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    public static void rebuild(File src, File dst, byte[] hook, Extra extra, Log log) throws IOException {
        RandomAccessFile in = new RandomAccessFile(src, "r");
        RandomAccessFile out = new RandomAccessFile(dst, "rw");
        out.setLength(0);
        try {
            List<Entry> entries = centralDir(in);
            boolean found = false;
            boolean dexDone = false, haveDex8 = false;
            Entry first7 = null;
            for (Entry e : entries) { if (e.name.equals("classes8.dex")) haveDex8 = true; if (e.name.equals("classes7.dex")) first7 = e; }
            if (extra != null && first7 != null) {
                byte[] r7 = read(in, dataStart(in, first7), first7.csize);
                String h7 = sha(first7.method == 0 ? r7 : inflate(r7, first7.usize));
                if (h7.equals(extra.origSha)) dexDone = true;
                else if (h7.equals(sha(extra.dex7))) log.line("Folder picker: already in this game, kept.");
                else log.line("Folder picker: skipped. This game's code is not the build I know (classes7 differs).");
            } else if (extra != null) log.line("Folder picker: skipped. No classes7.dex in this game.");
            if (dexDone && haveDex8) { log.line("Folder picker: skipped. classes8.dex already exists."); dexDone = false; }
            if (dexDone) {
                Entry n8 = new Entry();
                n8.name = "classes8.dex"; n8.nameBytes = n8.name.getBytes(UTF8); n8.method = 8; n8.version = 20;
                n8.time = first7.time; n8.date = first7.date; n8.comment = new byte[0]; n8.data = extra.dex8;
                entries.add(n8);
            }
            List<Entry> kept = new ArrayList<Entry>();
            List<Long> offsets = new ArrayList<Long>();
            byte[] buf = new byte[1 << 16];
            for (Entry e : entries) {
                if (isOldSignature(e.name) || e.name.endsWith("/")) continue;
                long start = e.data != null ? 0 : dataStart(in, e);
                byte[] newData = null;
                if (e.data != null || (dexDone && e.name.equals("classes7.dex"))) {
                    byte[] plain = e.data != null ? e.data : extra.dex7;
                    CRC32 c = new CRC32();
                    c.update(plain);
                    e.crc = c.getValue();
                    e.usize = plain.length;
                    newData = deflate(plain);
                    e.method = 8;
                    e.csize = newData.length;
                    if (e.data == null) log.line("Folder picker: game code patched (classes7.dex replaced, classes8.dex added).");
                }
                if (e.name.equals("AndroidManifest.xml")) {
                    byte[] raw = read(in, start, e.csize);
                    byte[] plain = e.method == 0 ? raw : inflate(raw, e.usize);
                    byte[] pm = patchManifest(plain, orientation, log);
                    if (pm == null) {
                        log.line("Landscape: skipped. This game's manifest is not the shape I know.");
                    } else {
                        CRC32 c = new CRC32();
                        c.update(pm);
                        e.crc = c.getValue();
                        e.usize = pm.length;
                        newData = e.method == 0 ? pm : deflate(pm);
                        e.csize = newData.length;
                        log.line(orientation == 2 ? "Landscape: the game may now turn with the phone." : "Landscape: off (the game stays portrait).");
                    }
                }
                if (e.name.equals(SCRIPT)) {
                    byte[] raw = read(in, start, e.csize);
                    byte[] plain = e.method == 0 ? raw : inflate(raw, e.usize);
                    byte[] patched = patchScript(plain, hook);
                    CRC32 c = new CRC32();
                    c.update(patched);
                    e.crc = c.getValue();
                    e.usize = patched.length;
                    if (e.method == 0) {         // libs stored (extractNativeLibs=false): keep it stored
                        newData = patched;
                    } else {
                        newData = deflate(patched);
                        e.method = 8;
                    }
                    e.csize = newData.length;
                    found = true;
                    log.line("Hook added to the game script (" + plain.length + " -> " + patched.length + " bytes).");
                }
                e.flags &= ~0x08;            // sizes are in the header, no data descriptor
                ByteArrayOutputStream h = new ByteArrayOutputStream(30 + e.nameBytes.length);
                w32(h, 0x04034b50L); w16(h, Math.max(20, e.version & 0xff)); w16(h, e.flags); w16(h, e.method);
                w16(h, e.time); w16(h, e.date); w32(h, e.crc); w32(h, e.csize); w32(h, e.usize);
                long off = out.getFilePointer();
                int pad = 0;
                if (e.method == 0) {        // stored: align data (4 bytes, native libs 4096) like zipalign
                    int align = pageAlign(e.name);
                    long dataAt = off + 30 + e.nameBytes.length;
                    pad = (int) ((align - (dataAt + 6) % align) % align) + 6;
                }
                w16(h, e.nameBytes.length); w16(h, pad);
                h.write(e.nameBytes);
                if (pad > 0) {
                    w16(h, 0xd935); w16(h, pad - 4); w16(h, pageAlign(e.name));
                    for (int z = 6; z < pad; z++) h.write(0);
                }
                out.write(h.toByteArray());
                if (newData != null) {
                    out.write(newData);
                } else {
                    in.seek(start);
                    long left = e.csize;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new IOException("APK ended early: " + e.name);
                        out.write(buf, 0, n);
                        left -= n;
                    }
                }
                kept.add(e);
                offsets.add(off);
            }
            if (!found) throw new IOException("This APK has no " + SCRIPT + ". It is not an FFBE Forever / Exvius Offline build this patcher knows.");
            long cdStart = out.getFilePointer();
            ByteArrayOutputStream cd = new ByteArrayOutputStream();
            for (int i = 0; i < kept.size(); i++) {
                Entry e = kept.get(i);
                w32(cd, 0x02014b50L); w16(cd, e.version); w16(cd, Math.max(20, e.version & 0xff)); w16(cd, e.flags);
                w16(cd, e.method); w16(cd, e.time); w16(cd, e.date); w32(cd, e.crc); w32(cd, e.csize); w32(cd, e.usize);
                w16(cd, e.nameBytes.length); w16(cd, 0); w16(cd, e.comment.length); w16(cd, 0); w16(cd, e.intAttr);
                w32(cd, e.extAttr); w32(cd, offsets.get(i));
                cd.write(e.nameBytes);
                cd.write(e.comment);
            }
            byte[] cdb = cd.toByteArray();
            out.write(cdb);
            ByteArrayOutputStream eo = new ByteArrayOutputStream();
            w32(eo, 0x06054b50L); w16(eo, 0); w16(eo, 0); w16(eo, kept.size()); w16(eo, kept.size());
            w32(eo, cdb.length); w32(eo, cdStart); w16(eo, 0);
            out.write(eo.toByteArray());
            if (out.length() > 0xffffffffL) throw new IOException("APK too large");
        } finally {
            in.close();
            out.close();
        }
    }

    /** Patches src into dst: rebuild, then sign with the editor's key (v2). */
    public static void patch(File src, File dst, byte[] hook, V2Signer signer, Log log) throws Exception {
        patch(src, dst, hook, null, signer, log);
    }

    public static void patch(File src, File dst, byte[] hook, Extra extra, V2Signer signer, Log log) throws Exception {
        File tmp = new File(dst.getPath() + ".unsigned");
        try {
            rebuild(src, tmp, hook, extra, log);
            log.line("Signing with the editor key...");
            signer.sign(tmp, dst);
        } finally {
            tmp.delete();
        }
    }
}
