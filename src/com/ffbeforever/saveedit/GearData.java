package com.ffbeforever.saveedit;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Description and stats text of gear and materia, bundled from the game tables (assets/geardata.json: id -> [description, stats]). */
final class GearData {
    private static Map<String, Object> data;

    static synchronized String[] get(Context c, String id) {
        if (data == null) {
            data = new HashMap<String, Object>();
            try {
                InputStream in = c.getApplicationContext().getAssets().open("geardata.json");
                ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
                byte[] b = new byte[1 << 16];
                int r;
                while ((r = in.read(b)) > 0) bo.write(b, 0, r);
                in.close();
                data = (Map<String, Object>) Json.parse(bo.toString("UTF-8"));
            } catch (Exception ignored) { }
        }
        Object o = data.get(id);
        if (!(o instanceof List)) return null;
        List<Object> l = (List<Object>) o;
        return new String[]{Editor.str(l.get(0)), Editor.str(l.get(1))};
    }
}
