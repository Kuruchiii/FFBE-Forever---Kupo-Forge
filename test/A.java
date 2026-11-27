import com.ffbeforever.saveedit.*;
import java.nio.file.*;
import java.util.*;
public class A {
    public static void main(String[] a) throws Exception {
        Db db = new Db(new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8"));
        Editor e = new Editor(db, new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8"));
        for (String n : new String[]{"Roselia", "Cecil", "Roselia"}) {
            Db.Unit d = null;
            for (Db.Unit x : db.units) if (x.name.equals(n)) { d = x; break; }
            Map<String, Object> u = e.addUnit(d, Math.min(d.maxStar(), 7));
            e.maxLevel(u, true);
            int r = e.tmr(u);
            System.out.println(e.label(u) + " lb " + u.get("lb_level") + " tmr " + r + " claimed " + u.get("affinity_reward_claimed")
                    + " owned " + e.owned(d.tmrKind, d.tmrId));
        }
        System.out.println("check=" + e.check());
        Files.write(Paths.get(a[2]), e.text().getBytes("UTF-8"));
    }
}
