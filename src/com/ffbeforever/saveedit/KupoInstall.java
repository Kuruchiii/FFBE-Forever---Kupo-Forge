package com.ffbeforever.saveedit;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Install Script": sends the signed hook script that was bundled at build time to the game's third-party tools loader
 * (127.0.0.1:27334), so the game does not have to be patched by hand. The app holds only the finished, signed file
 * (assets/kupo_payload.bin: 4-byte length 256, 256-byte signature, then the script text whose first line is the signed header).
 * The private signing key is never in the app. The game accepts a script only if the signature fits the key it has on its
 * allowed list, the header names this tool, and the script has not expired (the game allows at most 120 days).
 */
final class KupoInstall {
    static final int PORT = 27334;
    private static final long SIG = 4 + 256;

    private KupoInstall() { }

    private static byte[] payload(Context c, String name) {
        try {
            InputStream in = c.getAssets().open(name);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            return b.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Expiry (unix seconds) from the signed header line, or -1. */
    static long expiry(byte[] p) {
        try {
            if (p == null || p.length < SIG + 20) return -1;
            int end = (int) SIG;
            while (end < p.length && end < SIG + 300 && p[end] != '\n') end++;
            String head = new String(p, (int) SIG, end - (int) SIG, "UTF-8");
            Matcher m = Pattern.compile("exp=(\\d+)").matcher(head);
            return m.find() ? Long.parseLong(m.group(1)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String day(long unix) {
        return new SimpleDateFormat("d MMM yyyy", Locale.US).format(new Date(unix * 1000L));
    }

    private static void note(Context c, String line) {
        try {
            File f = new File(c.getFilesDir(), "install_script.log");
            String old = "";
            if (f.exists()) {
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                in.close();
                old = new String(b.toByteArray(), "UTF-8");
                if (old.length() > 12000) old = old.substring(old.length() - 8000);
            }
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            FileOutputStream o = new FileOutputStream(f);
            o.write((old + stamp + " " + line.replace('\n', ' ') + "\n").getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) { }
    }

    /** The answer port is plain ASCII: write every other character as \\uXXXX (valid inside JSON strings). */
    static String ascii(String t) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch < 32 || ch > 126) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch);
        }
        return b.toString();
    }

    static boolean hooksUp() { return hooksAnswer(); }

    private static boolean hooksAnswer() {
        try { String r = Speed.send("?"); return r != null && r.startsWith("OK "); } catch (Exception e) { return false; }
    }

    /** Is the loader's door open right now? (a plain connect, nothing is sent) */
    static boolean doorOpen() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 300);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** Starts the game, waits for the script door to open (the loader waits for the game library, up to 2 minutes), then installs. */
    static String startAndInstall(Context c, String optJson) {
        try {
            android.content.Intent li = c.getPackageManager().getLaunchIntentForPackage("com.exvius.offline.forever");
            if (li == null) return "The game (com.exvius.offline.forever) is not installed.";
            li.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(li);
        } catch (Exception e) {
            return "Could not start the game: " + e.getMessage();
        }
        long end = System.currentTimeMillis() + 130000;
        while (System.currentTimeMillis() < end) {
            if (doorOpen()) { try { Thread.sleep(1500); } catch (InterruptedException ignored) { } return run(c, optJson); }
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
        }
        return "The game started but its script door never opened in 2 minutes. " + HINT;
    }

    static final String HINT = "In the game open Settings > Troubleshooting > Third-party tools, turn it on, then close the game "
            + "completely and open it again.";

    /** Does the whole job and returns a plain-language result. Call from a background thread. */
    static String run(Context c, String optJson) {
        String out = go(c, optJson);
        note(c, out);
        return out;
    }

    private static String go(Context c, String optJson) {
        byte[] keep = payload(c, "kupo_payload_keep.bin");
        if (keep != null) {
            String r = go2(c, optJson, keep, true);
            // an older game build refuses keep=1 ("refused ... header"): then send the normal script
            if (r != null) return r;
        }
        String r = go2(c, optJson, payload(c, "kupo_payload.bin"), false);
        return r == null ? "The game refused the script." : r;
    }

    /** Plain status for the Patch tab: how long a saved script lasts. */
    static String status(Context c) {
        long e = c.getSharedPreferences("editor", Context.MODE_PRIVATE).getLong("keptUntil", 0);
        if (e <= 0) return "";
        long now = System.currentTimeMillis() / 1000;
        if (now > e) return "The saved script in the game expired on " + day(e) + ". Update the app and tap Install Script.";
        String s = "Script saved in the game until " + day(e) + ".";
        if (e - now < 14 * 86400L) s += " Update the app before then.";
        return s;
    }

    /** Returns null only when keep is true and the game build is old (so the caller falls back). */
    private static String go2(Context c, String optJson, byte[] p, boolean keepTry) {
        if (p == null || p.length < SIG + 20) return "This build of the app has no signed script inside (it was built without the signing key). Install the official build.";
        long exp = expiry(p), now = System.currentTimeMillis() / 1000;
        if (exp > 0 && now > exp) return "The script inside this app expired on " + day(exp) + ", so the game would refuse it. Install the newest version of the app.";
        Socket s = new Socket();
        String reply;
        try {
            try {
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
            } catch (ConnectException e) {
                return noLoader("refused (nothing listens on port " + PORT + ")");
            } catch (SocketTimeoutException e) {
                return noLoader("timed out");
            }
            s.setSoTimeout(8000);
            OutputStream o = s.getOutputStream();
            o.write(p);
            o.flush();
            try { s.shutdownOutput(); } catch (Exception ignored) { }   // the loader reads until the sender closes its side
            InputStream in = s.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int ch;
            while ((ch = in.read()) >= 0 && ch != '\n') b.write(ch);
            reply = b.toString("UTF-8").trim();
        } catch (SocketTimeoutException e) {
            return "The game did not answer within 8 seconds. Try again.";
        } catch (Exception e) {
            return "Could not talk to the game: " + e.getMessage() + ". Try again.";
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
        boolean silent = reply.length() == 0;   // the loader may run the script but fail to send its reply: then the script itself is the proof
        if (reply.startsWith("ok") || silent) {
            // The script waits for the player's Patch List choices, then starts its features. Send them (the game's own answer port opens a moment later).
            String optReply = "";
            for (int i = 0; i < 10 && !optReply.startsWith("ok"); i++) {
                try { optReply = Speed.send("opt " + ascii(optJson)); } catch (Exception e) { optReply = ""; }
                if (!optReply.startsWith("ok")) { try { Thread.sleep(1000); } catch (InterruptedException ignored) { } }
            }
            if (!optReply.startsWith("ok")) {
                if (silent && keepTry) return null;   // old build with the broken reply path: it never ran the keep script
                if (silent) return "The game closed the connection without an answer (sent " + p.length + " bytes) and the script did not start. Try again. If it repeats, send me install_script.log and the game's export log.";
                return "The game accepted the script (" + reply + ") but did not take your Patch List choices (" + (optReply.length() == 0 ? "no answer" : optReply) + "). Tap Install Script again.";
            }
            for (int i = 0; i < 8; i++) {
                if (hooksAnswer()) {
                    android.content.SharedPreferences.Editor ed = c.getSharedPreferences("editor", Context.MODE_PRIVATE).edit();
                    if (keepTry && exp > 0) ed.putLong("keptUntil", exp); else ed.remove("keptUntil");
                    ed.apply();
                }
                if (hooksAnswer()) return "Done. The script is installed and the game answers." + (keepTry && exp > 0 ? " It is saved in the game until " + day(exp) + ", so it starts by itself every time. Update the app before then." : exp > 0 ? " It works until " + day(exp) + " (then update the app, and tap Install Script after every game start)." : "") + " Raw reply: " + (silent ? "(none, the loader sent no reply but the script runs)" : reply);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            }
            return "The game accepted the script (" + reply + ") but its hooks did not answer in 8 seconds. Open the game's home screen and look for the floating button. If it never shows, send me the log.";
        }
        if (reply.length() == 0) return "The game closed the connection without an answer (sent " + p.length + " bytes). Try again. If it repeats, send me the log (install_script.log and the game's KUPO_LOADER lines).";
        if (reply.contains("signature rejected")) return "Rejected: this game build does not have our key on its allowed list. Use the game build the developer made for Kupo Forge. (" + reply + ")";
        if (reply.contains("expired")) return "Rejected: the script has expired. Install the newest version of the app. (" + reply + ")";
        if (reply.contains("expiry too far")) return "Rejected: the script's expiry is longer than the game allows (120 days). The app build is wrong, tell me. (" + reply + ")";
        if (keepTry && reply.contains("header")) return null;   // old game build: it does not know keep=1
        if (reply.contains("header")) return "Rejected: the signed header is missing or wrong. The app build is wrong, tell me. (" + reply + ")";
        if (reply.contains("timeout")) return "Rejected: the game waited too long for the data. Try again. (" + reply + ")";
        if (reply.contains("too large") || reply.contains("short") || reply.contains("bad signature length")) return "Rejected: the file was damaged on the way. Try again. (" + reply + ")";
        if (reply.startsWith("script error")) return "The game accepted the script, but it failed while starting: " + reply;
        return "The game said: " + reply;
    }

    private static String noLoader(String why) {
        boolean running = hooksAnswer();
        if (running) return "The game is running and already has our hooks (it was patched), so there is nothing to install. "
                + "To use Install Script instead, install the developer's game build and turn on Third-party tools in its settings.";
        return "Can not reach the game's script door (" + why + "). Check these three things:\n1. The game is open.\n2. " + HINT
                + "\n3. You are using the game build that the developer made for Kupo Forge.";
    }
}
