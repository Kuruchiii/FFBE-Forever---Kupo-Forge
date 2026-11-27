package com.ffbeforever.saveedit;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;

/** FFBE-style look: deep navy windows, blue buttons with a gold edge, cream text (gold taken from the icon frame). */
final class Ui {
    static final int NIGHT = 0xff070d24, NAVY = 0xff12204a, PANEL = 0xff1a2c5e;
    static final int BTN_TOP = 0xff2c4c94, BTN_BOT = 0xff142a63, BTN_PRESS = 0xff3b63b8;
    static final int GOLD = 0xffd4b46a, CREAM = 0xfff2dea5, TEXT = 0xfff5efe0, DIM = 0xff8e9cc4;

    private Ui() { }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    private static GradientDrawable plate(Context c, int top, int bottom, int edge) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        g.setCornerRadius(dp(c, 6));
        g.setStroke(Math.max(1, dp(c, 1)), edge);
        return g;
    }

    static Drawable buttonBg(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{-android.R.attr.state_enabled}, plate(c, 0xff2a2f40, 0xff1c202c, 0xff5a5a5a));
        s.addState(new int[]{android.R.attr.state_pressed}, plate(c, BTN_PRESS, BTN_TOP, CREAM));
        s.addState(new int[0], plate(c, BTN_TOP, BTN_BOT, GOLD));
        int i = dp(c, 3);
        return new InsetDrawable(s, i, i, i, i);
    }

    /** Same plate as buttonBg (gold edge, gradient fill) with a red fill. */
    static Drawable redBg(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, plate(c, 0xffe0584a, 0xffa22a28, CREAM));
        s.addState(new int[0], plate(c, 0xffb83a36, 0xff6a1519, GOLD));
        int i = dp(c, 3);
        return new InsetDrawable(s, i, i, i, i);
    }

    /** Same plate as buttonBg with a green fill (Start game). */
    static Drawable greenBg(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, plate(c, 0xff4fbf5a, 0xff2a8a35, CREAM));
        s.addState(new int[0], plate(c, 0xff2f9e3c, 0xff1b6a26, GOLD));
        int i = dp(c, 3);
        return new InsetDrawable(s, i, i, i, i);
    }

    /** Patch List header plates: 0 all off (slate), 1 some on (amber), 2 all on (green). */
    static Drawable stateBg(Context c, int st) {
        if (st == 2) return greenBg(c);
        StateListDrawable s = new StateListDrawable();
        int a = st == 1 ? 0xffe0a233 : 0xff4a5878, b = st == 1 ? 0xffa86d12 : 0xff2a3349;
        s.addState(new int[]{android.R.attr.state_pressed}, plate(c, st == 1 ? 0xfff0b648 : 0xff5d6e92, a, CREAM));
        s.addState(new int[0], plate(c, a, b, GOLD));
        int i = dp(c, 3);
        return new InsetDrawable(s, i, i, i, i);
    }

    /** Gold circle with a slash: tap to disable. */
    static Drawable disableIcon(final Context c, float dps) { return ring(c, dps, false); }
    /** Gold circle with a check: tap to enable. */
    static Drawable enableIcon(final Context c, float dps) { return ring(c, dps, true); }

    private static Drawable ring(final Context c, final float dps, final boolean check) {
        final float sz = dp(c, dps);
        return new Drawable() {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            { p.setColor(GOLD); p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(Math.max(1.5f, sz * 0.12f)); p.setStrokeCap(android.graphics.Paint.Cap.ROUND); }
            public void draw(android.graphics.Canvas cv) {
                android.graphics.Rect r = getBounds();
                float x = r.left, y = r.top, w = r.width(), h = r.height();
                cv.drawCircle(x + w / 2, y + h / 2, w * 0.42f, p);
                if (check) {
                    android.graphics.Path k = new android.graphics.Path();
                    k.moveTo(x + w * 0.28f, y + h * 0.52f);
                    k.lineTo(x + w * 0.44f, y + h * 0.68f);
                    k.lineTo(x + w * 0.74f, y + h * 0.34f);
                    cv.drawPath(k, p);
                } else {
                    cv.drawLine(x + w * 0.22f, y + h * 0.78f, x + w * 0.78f, y + h * 0.22f, p);
                }
            }
            public void setAlpha(int a) { p.setAlpha(a); }
            public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
            public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            public int getIntrinsicWidth() { return (int) sz; }
            public int getIntrinsicHeight() { return (int) sz; }
        };
    }

    /** Gold silhouette of three people: "this unit is in a party". */
    static Drawable partyIcon(final Context c, float dps) {
        final float sz = dp(c, dps);
        return new Drawable() {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            { p.setColor(GOLD); }
            void person(android.graphics.Canvas cv, float cx, float top, float s) {
                cv.drawCircle(cx, top + s * 0.22f, s * 0.2f, p);
                android.graphics.RectF b = new android.graphics.RectF(cx - s * 0.32f, top + s * 0.46f, cx + s * 0.32f, top + s * 1.1f);
                cv.drawRoundRect(b, s * 0.3f, s * 0.3f, p);
            }
            public void draw(android.graphics.Canvas cv) {
                android.graphics.Rect r = getBounds();
                float x = r.left, y = r.top, w = r.width(), h = r.height();
                p.setAlpha(190);
                person(cv, x + w * 0.2f, y + h * 0.22f, w * 0.5f);     // back left
                person(cv, x + w * 0.8f, y + h * 0.22f, w * 0.5f);     // back right
                p.setAlpha(255);
                person(cv, x + w * 0.5f, y + h * 0.02f, w * 0.62f);    // front
            }
            public void setAlpha(int a) { p.setAlpha(a); }
            public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
            public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            public int getIntrinsicWidth() { return (int) sz; }
            public int getIntrinsicHeight() { return (int) sz; }
        };
    }

    /** Fallback pictures for awakening materials (the game has none): prism = blue-violet diamond, fragment = amber shards, Lapis = sky gem, Gil = gold coin, other = silver tile. */
    static Drawable matIcon(final Context c, final String kind, final String id) {
        return new Drawable() {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            public void draw(android.graphics.Canvas cv) {
                android.graphics.Rect r = getBounds();
                float x = r.left, y = r.top, w = r.width(), h = r.height();
                android.graphics.Path d = new android.graphics.Path();
                if (id.equals("gil")) {
                    p.setColor(0xffd4b46a); cv.drawCircle(x + w / 2, y + h / 2, w * 0.38f, p);
                    p.setColor(0xff8a6d2a); p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(w * 0.06f);
                    cv.drawCircle(x + w / 2, y + h / 2, w * 0.26f, p); p.setStyle(android.graphics.Paint.Style.FILL);
                    return;
                }
                if (kind.equals("pot")) {   // green jar
                    p.setColor(0xff5fc47a); cv.drawCircle(x + w * 0.5f, y + h * 0.62f, w * 0.30f, p);
                    p.setColor(0xff3d8f56); cv.drawRect(x + w * 0.40f, y + h * 0.14f, x + w * 0.60f, y + h * 0.36f, p);
                    p.setColor(0x55ffffff); cv.drawCircle(x + w * 0.42f, y + h * 0.55f, w * 0.08f, p);
                    return;
                }
                if (kind.equals("door")) {   // gold arched door
                    p.setColor(0xffd4b46a);
                    cv.drawRect(x + w * 0.22f, y + h * 0.36f, x + w * 0.78f, y + h * 0.92f, p);
                    cv.drawCircle(x + w * 0.5f, y + h * 0.36f, w * 0.28f, p);
                    p.setColor(0xff1a2c5e);
                    cv.drawRect(x + w * 0.36f, y + h * 0.42f, x + w * 0.64f, y + h * 0.92f, p);
                    cv.drawCircle(x + w * 0.5f, y + h * 0.42f, w * 0.14f, p);
                    return;
                }
                int col = id.equals("lapis_free") ? 0xff5ac8ff : kind.equals("prism") ? 0xff8f7cff : kind.equals("fragment") ? 0xffe8a838 : 0xffb8c0d8;
                if (kind.equals("fragment")) {
                    p.setColor(col);
                    d.moveTo(x + w * 0.30f, y + h * 0.18f); d.lineTo(x + w * 0.52f, y + h * 0.55f); d.lineTo(x + w * 0.10f, y + h * 0.62f); d.close();
                    cv.drawPath(d, p);
                    d.reset(); d.moveTo(x + w * 0.66f, y + h * 0.30f); d.lineTo(x + w * 0.92f, y + h * 0.78f); d.lineTo(x + w * 0.46f, y + h * 0.86f); d.close();
                    cv.drawPath(d, p);
                } else if (kind.equals("other") && !id.equals("lapis_free")) {
                    p.setColor(col);
                    cv.drawRoundRect(new android.graphics.RectF(x + w * 0.18f, y + h * 0.18f, x + w * 0.82f, y + h * 0.82f), w * 0.12f, w * 0.12f, p);
                } else {
                    p.setColor(col);
                    d.moveTo(x + w * 0.5f, y + h * 0.06f); d.lineTo(x + w * 0.9f, y + h * 0.42f); d.lineTo(x + w * 0.5f, y + h * 0.94f); d.lineTo(x + w * 0.1f, y + h * 0.42f); d.close();
                    cv.drawPath(d, p);
                    p.setColor(0x55ffffff);
                    d.reset(); d.moveTo(x + w * 0.5f, y + h * 0.06f); d.lineTo(x + w * 0.1f, y + h * 0.42f); d.lineTo(x + w * 0.5f, y + h * 0.42f); d.close();
                    cv.drawPath(d, p);
                }
            }
            public void setAlpha(int a) { p.setAlpha(a); }
            public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
            public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    /** A small gold sword and shield mark for "wears gear". */
    static Drawable gearIcon(final Context c, float dps) {
        return new Drawable() {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            { p.setColor(GOLD); }
            public void draw(android.graphics.Canvas cv) {
                android.graphics.Rect r = getBounds();
                float x = r.left, y = r.top, w = r.width(), h = r.height();
                android.graphics.Path sh = new android.graphics.Path();
                sh.moveTo(x + w * 0.10f, y + h * 0.12f);
                sh.lineTo(x + w * 0.90f, y + h * 0.12f);
                sh.lineTo(x + w * 0.90f, y + h * 0.50f);
                sh.quadTo(x + w * 0.90f, y + h * 0.82f, x + w * 0.50f, y + h * 0.96f);
                sh.quadTo(x + w * 0.10f, y + h * 0.82f, x + w * 0.10f, y + h * 0.50f);
                sh.close();
                p.setStyle(android.graphics.Paint.Style.FILL);
                cv.drawPath(sh, p);
                p.setColor(0xff2a3566);
                cv.drawRect(x + w * 0.46f, y + h * 0.24f, x + w * 0.54f, y + h * 0.78f, p);
                cv.drawRect(x + w * 0.30f, y + h * 0.36f, x + w * 0.70f, y + h * 0.43f, p);
                p.setColor(GOLD);
            }
            public void setAlpha(int a) { p.setAlpha(a); }
            public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
            public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            public int getIntrinsicWidth() { return (int) dp(c, dps); }
            public int getIntrinsicHeight() { return (int) dp(c, dps); }
        };
    }

    /** A funnel (filter) icon in gold, 22dp. */
    static Drawable funnel(final Context c, float dps) {
        final float sz = dp(c, dps);
        return new Drawable() {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            { p.setColor(GOLD); }
            public void draw(android.graphics.Canvas cv) {
                android.graphics.Rect r = getBounds();
                float x = r.left, y = r.top, w = r.width(), h = r.height();
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(x + w * 0.04f, y + h * 0.12f);
                path.lineTo(x + w * 0.96f, y + h * 0.12f);
                path.lineTo(x + w * 0.60f, y + h * 0.54f);
                path.lineTo(x + w * 0.60f, y + h * 0.90f);
                path.lineTo(x + w * 0.40f, y + h * 0.78f);
                path.lineTo(x + w * 0.40f, y + h * 0.54f);
                path.close();
                cv.drawPath(path, p);
            }
            public void setAlpha(int a) { p.setAlpha(a); }
            public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
            public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            public int getIntrinsicWidth() { return (int) sz; }
            public int getIntrinsicHeight() { return (int) sz; }
        };
    }

    /** A trash can (delete) icon in gold. */
    static Drawable trash(final Context c, float dps) {
        final float sz = dp(c, dps);
        return new Drawable() {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            { p.setColor(GOLD); }
            public void draw(android.graphics.Canvas cv) {
                android.graphics.Rect r = getBounds();
                float x = r.left, y = r.top, w = r.width(), h = r.height();
                cv.drawRect(x + w * 0.10f, y + h * 0.18f, x + w * 0.90f, y + h * 0.28f, p);          // lid
                cv.drawRect(x + w * 0.36f, y + h * 0.08f, x + w * 0.64f, y + h * 0.18f, p);          // handle
                android.graphics.Path b = new android.graphics.Path();                                // body
                b.moveTo(x + w * 0.18f, y + h * 0.34f);
                b.lineTo(x + w * 0.82f, y + h * 0.34f);
                b.lineTo(x + w * 0.74f, y + h * 0.94f);
                b.lineTo(x + w * 0.26f, y + h * 0.94f);
                b.close();
                cv.drawPath(b, p);
            }
            public void setAlpha(int a) { p.setAlpha(a); }
            public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
            public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            public int getIntrinsicWidth() { return (int) sz; }
            public int getIntrinsicHeight() { return (int) sz; }
        };
    }

    static ColorStateList gold() { return ColorStateList.valueOf(GOLD); }

    /** Style a view tree built in code. Adapter rows are styled by {@link #adapter}. */
    static void apply(View v) {
        Context c = v.getContext();
        if (v instanceof CompoundButton) {               // CheckBox, Switch (before Button: they extend it)
            CompoundButton b = (CompoundButton) v;
            b.setButtonTintList(gold());
            b.setTextColor(TEXT);
        } else if (v instanceof Button) {
            Button b = (Button) v;
            b.setBackground(buttonBg(c));
            b.setTextColor(CREAM);
            b.setMinHeight(dp(c, 46));
            b.setStateListAnimator(null);
        } else if (v instanceof EditText) {
            EditText e = (EditText) v;
            e.setBackgroundTintList(gold());
            e.setTextColor(TEXT);
            e.setHintTextColor(DIM);
        } else if (v instanceof TextView) {
            if (!"keep".equals(v.getTag())) ((TextView) v).setTextColor(TEXT);
        } else if (v instanceof Spinner) {
            Spinner s = (Spinner) v;
            s.setBackgroundTintList(gold());
            s.setPopupBackgroundDrawable(new ColorDrawable(PANEL));
        } else if (v instanceof ListView) {
            ListView l = (ListView) v;
            l.setDivider(new ColorDrawable(0x40d4b46a));
            l.setDividerHeight(Math.max(1, dp(c, 1)));
        }
        if (v instanceof ViewGroup && !(v instanceof ListView) && !(v instanceof Spinner)) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }

    static void row(View v) {
        if (v instanceof CheckedTextView) ((CheckedTextView) v).setCheckMarkTintList(gold());
        if (v instanceof TextView) ((TextView) v).setTextColor(TEXT);
    }

    /** ArrayAdapter whose rows (and spinner drop-down rows) use the theme colors. */
    static ArrayAdapter<String> adapter(Context c, int layout, List<String> items) {
        return new ArrayAdapter<String>(c, layout, items) {
            public View getView(int pos, View old, ViewGroup parent) {
                View v = super.getView(pos, old, parent);
                row(v);
                return v;
            }
            public View getDropDownView(int pos, View old, ViewGroup parent) {
                View v = super.getDropDownView(pos, old, parent);
                row(v);
                return v;
            }
        };
    }
}
