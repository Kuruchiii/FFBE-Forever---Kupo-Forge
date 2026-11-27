package com.ffbeforever.patch;

import android.content.Context;
import android.os.Environment;
import android.webkit.JavascriptInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

/** Page-side helper for the FFBE Forever settings page: shows and changes the export / log folder. */
public final class ExportBridge {
    private final File cfgFile;

    public ExportBridge(Context c) {
        cfgFile = new File(c.getFilesDir(), "ffbe_export_dir.txt");
        ExportDir.cfg = cfgFile;
    }

    private static File root() { return Environment.getExternalStorageDirectory(); }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 32) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** Text shown on the page: the folder in use. */
    @JavascriptInterface
    public String current() {
        String rel = ExportDir.chosen();
        if (rel.length() == 0) return "Downloads folder (default)";
        return new File(root(), rel).getPath().replace("/storage/emulated/0/", "Internal storage/");
    }

    /** Sub-folders of a folder, as a JSON array of names. "" lists the two allowed roots. */
    @JavascriptInterface
    public String list(String rel) {
        try {
            rel = ExportDir.clean(rel == null ? "" : rel);
            if (rel.length() == 0) return "[\"Download\",\"Documents\"]";
            File[] fs = new File(root(), rel).listFiles();
            StringBuilder b = new StringBuilder("[");
            if (fs != null) {
                String[] names = new String[fs.length];
                int n = 0;
                for (File f : fs) if (f.isDirectory() && !f.getName().startsWith(".")) names[n++] = f.getName();
                names = Arrays.copyOf(names, n);
                Arrays.sort(names, String.CASE_INSENSITIVE_ORDER);
                for (int i = 0; i < n; i++) b.append(i > 0 ? "," : "").append(q(names[i]));
            }
            return b.append("]").toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    /** Makes a sub-folder. Returns "OK" or "ERR reason". */
    @JavascriptInterface
    public String mkdir(String rel, String name) {
        try {
            rel = ExportDir.clean(rel == null ? "" : rel);
            if (rel.length() == 0) return "ERR open Download or Documents first";
            name = name == null ? "" : name.trim();
            if (name.length() == 0 || name.contains("/") || name.contains("\\") || name.startsWith(".")) return "ERR bad folder name";
            File f = new File(new File(root(), rel), name);
            if (f.isDirectory() || f.mkdirs()) return "OK";
            return "ERR could not create the folder";
        } catch (Throwable t) {
            return "ERR " + t;
        }
    }

    /** Uses this folder after a write test. Returns "OK <text>" or "ERR reason". */
    @JavascriptInterface
    public String choose(String rel) {
        try {
            rel = ExportDir.clean(rel == null ? "" : rel);
            if (rel.length() == 0) return "ERR pick a folder inside Download or Documents";
            File d = new File(root(), rel);
            if (!d.isDirectory() && !d.mkdirs()) return "ERR the folder does not exist";
            File t = new File(d, ".ffbe_write_test");
            FileOutputStream o = new FileOutputStream(t);
            o.write(1);
            o.close();
            t.delete();
            FileOutputStream c = new FileOutputStream(cfgFile);
            c.write((rel + "\n").getBytes("UTF-8"));
            c.close();
            return "OK " + current();
        } catch (Throwable t) {
            return "ERR Android would not let the game write there (" + t.getMessage() + "). Keeping the old folder.";
        }
    }

    /** Back to the Downloads folder. */
    @JavascriptInterface
    public String reset() {
        try {
            cfgFile.delete();
            return "OK " + current();
        } catch (Throwable t) {
            return "ERR " + t;
        }
    }

    /** Drop-in used on the settings page string: adds the folder card under Import Code. */
    public static String page(String html) {
        try {
            if (html == null || !html.contains("importEra")) return html;
            String s = "<script>" + PageJs.JS + "</script>";
            int i = html.lastIndexOf("</body>");
            return i >= 0 ? html.substring(0, i) + s + html.substring(i) : html + s;
        } catch (Throwable t) {
            return html;
        }
    }
}
