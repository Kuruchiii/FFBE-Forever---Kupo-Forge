package com.ffbeforever.patch;

import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/** Where the game puts save exports and logs, and where Import looks. Default stays the Downloads folder. */
public final class ExportDir {
    static final String PKG_CFG = "/data/data/com.exvius.offline.forever/files/ffbe_export_dir.txt";
    static volatile File cfg;

    private ExportDir() { }

    /** Folder chosen by the player, relative to shared storage (e.g. "Documents/FFBE"), or "" for the default. */
    static String chosen() {
        try {
            File f = cfg != null ? cfg : new File(PKG_CFG);
            if (!f.isFile()) return "";
            BufferedReader r = new BufferedReader(new FileReader(f));
            try {
                String s = r.readLine();
                return s == null ? "" : clean(s);
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /** Only folders under Download or Documents, no "..": the only places an app may write non-media files. */
    static String clean(String s) {
        s = s.trim().replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.length() == 0 || s.contains("..") || s.contains("//")) return "";
        if (!(s.equals("Download") || s.startsWith("Download/") || s.equals("Documents") || s.startsWith("Documents/"))) return "";
        return s;
    }

    /** Drop-in for Environment.getExternalStoragePublicDirectory(type). Falls back to it on any problem. */
    public static File dir(String type) {
        File base = Environment.getExternalStoragePublicDirectory(type);
        try {
            if (!Environment.DIRECTORY_DOWNLOADS.equals(type)) return base;
            String rel = chosen();
            if (rel.length() == 0) return base;
            File f = new File(Environment.getExternalStorageDirectory(), rel);
            if (!f.isDirectory()) f.mkdirs();
            if (f.isDirectory() && f.canWrite()) return f;
        } catch (Throwable t) {
            // fall through to the default
        }
        return base;
    }

    /** Drop-in for the MediaStore relative_path value used for the log export. */
    public static String rel(String type) {
        try {
            if (!Environment.DIRECTORY_DOWNLOADS.equals(type)) return type;
            String rel = chosen();
            if (rel.length() != 0) return rel;
        } catch (Throwable t) {
            // fall through
        }
        return type;
    }
}
