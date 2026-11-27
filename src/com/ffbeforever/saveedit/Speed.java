package com.ffbeforever.saveedit;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Talks to the speed hook inside the patched game (127.0.0.1:27333). */
public final class Speed {
    public static final int PORT = 27333;

    /** Where the last in-game patch log is kept, so it can still be exported after the game crashed or closed. Set once by the app or the helper. */
    public static volatile java.io.File logCache;

    /** Reads the in-game log. When the game is not running, returns the last copy kept on disk (marked on the first line); fails only if there is none. */
    public static String qlog(int waitMs) throws Exception {
        try {
            String r = sendAll("qlog", waitMs);
            if (r != null && r.length() > 20) saveLog(r);
            return r;
        } catch (Exception e) {
            java.io.File f = logCache;
            if (f != null && f.exists() && f.length() > 0) {
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                in.close();
                return "(game not running: this is the last copy kept by the app, saved "
                        + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date(f.lastModified())) + ")\n" + b.toString("UTF-8");
            }
            throw e;
        }
    }

    private static void saveLog(String r) {
        try {
            java.io.File f = logCache;
            if (f == null) return;
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            o.write(r.getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) { }
    }

    /** Sends a command ("3", "1.5", or "?" to only ask). Returns the game's reply, e.g. "OK 3 ready". */
    public static String send(String cmd) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
            s.setSoTimeout(2000);
            OutputStream o = s.getOutputStream();
            o.write((cmd + "\n").getBytes("US-ASCII"));
            o.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0 && c != '\n') b.write(c);
            return b.toString("US-ASCII").trim();
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** Like send, for big commands or replies (save hand-over). One line out, one line back, long wait. */
    public static String sendBig(String cmd, int waitMs) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
            s.setSoTimeout(waitMs);
            OutputStream o = new java.io.BufferedOutputStream(s.getOutputStream(), 65536);
            o.write((cmd + "\n").getBytes("US-ASCII"));
            o.flush();
            InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 65536);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0 && c != '\n') b.write(c);
            return b.toString("US-ASCII").trim();
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** Sends a command and returns the whole multi-line reply (used by the file survey). */
    public static String sendAll(String cmd, int waitMs) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
            s.setSoTimeout(waitMs);
            s.getOutputStream().write((cmd + "\n").getBytes("US-ASCII"));
            s.getOutputStream().flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8").trim();
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** Short text for a speed value: 2 -> "2x", 1.5 -> "1.5x". */
    public static String label(double v) {
        if (v == Math.floor(v)) return ((long) v) + "x";
        return v + "x";
    }
}
