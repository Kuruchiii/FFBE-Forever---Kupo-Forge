package com.ffbeforever.saveedit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Gets the result of a game install started from the patcher. */
public class InstallReceiver extends BroadcastReceiver {
    public interface Listener { void result(String text); }
    static Listener listener;

    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // Android 16 blocks launching an intent taken from another intent's extras unless the app
                // says it is intended. This one is the system installer's own confirm screen.
                if (android.os.Build.VERSION.SDK_INT >= 36) {
                    try { Intent.class.getMethod("removeLaunchSecurityProtection").invoke(confirm); } catch (Exception ignored) { }
                }
                c.startActivity(confirm);
            }
            return;
        }
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String text;
        if (status == PackageInstaller.STATUS_SUCCESS) {
            new java.io.File(c.getCacheDir(), "patched.apk").delete();
            text = "Patched game installed. The temporary patched copy was deleted.";
        } else if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
            text = "Android refused the install: the game on your phone is signed with a different key. "
                    + "Export your save from the title screen, uninstall the game, then tap Install again. (" + msg + ")";
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            text = "Install cancelled.";
        } else if (status == PackageInstaller.STATUS_FAILURE_STORAGE) {
            text = "Not enough storage to install. (" + msg + ")";
        } else {
            text = "Install failed (code " + status + "): " + msg;
        }
        Toast.makeText(c, text, Toast.LENGTH_LONG).show();
        if (listener != null) listener.result(text);
    }
}
