package com.ffbeforever.saveedit;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.TextView;

/** Dialog builder for every popup in the tool: blue background, gold border, gold title and buttons. */
final class Dlg extends AlertDialog.Builder {
    private final Context cx;

    Dlg(Context c) {
        super(c);
        cx = c;
    }

    public AlertDialog create() {
        AlertDialog d = super.create();
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.NAVY);
        g.setStroke(Math.max(2, Ui.dp(cx, 2)), Ui.GOLD);
        g.setCornerRadius(Ui.dp(cx, 10));
        d.getWindow().setBackgroundDrawable(g);
        return d;
    }

    public AlertDialog show() {
        AlertDialog d = create();
        d.show();
        style(d);
        return d;
    }

    /** Colours the title, message and buttons of a dialog that is already showing. */
    static void style(AlertDialog d) {
        Context c = d.getContext();
        TextView t = (TextView) d.findViewById(c.getResources().getIdentifier("alertTitle", "id", "android"));
        if (t != null) { t.setTextColor(Ui.GOLD); t.setTypeface(Typeface.SERIF, Typeface.BOLD); }
        TextView m = (TextView) d.findViewById(android.R.id.message);
        if (m != null) m.setTextColor(Ui.TEXT);
        for (int b : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button bt = d.getButton(b);
            if (bt != null) bt.setTextColor(Ui.GOLD);
        }
    }
}
