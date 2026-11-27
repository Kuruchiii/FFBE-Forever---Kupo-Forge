import com.ffbeforever.saveedit.*;
import java.nio.file.*;
import java.util.*;
public class U {
    public static void main(String[] a) throws Exception {
        Db db = new Db(new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8"));
        Editor e = new Editor(db, new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8"));
        int before = e.roster.size();
        String[][] picks = {{"Roselia", "base"}, {"Roselia", "7"}, {"Cecil", "max"}, {"Rain", "base"}, {"Trust Moogle", "base"}};
        for (String[] p : picks) {
            Db.Unit d = null;
            for (Db.Unit x : db.units) if (x.name.equals(p[0])) { d = x; break; }
            int want = p[1].equals("base") ? d.minStar() : p[1].equals("max") ? d.maxStar() : Integer.parseInt(p[1]);
            int had = e.ownedCount(d);
            Map<String, Object> u = e.addUnit(d, want);
            if (p[1].equals("max")) e.maxLevel(u, true);
            System.out.println(p[0] + " want " + want + " -> " + e.label(u) + " id " + u.get("instance_id") + " have " + had + "->" + e.ownedCount(d));
        }
        System.out.println("roster " + before + " -> " + e.roster.size() + "  check=" + e.check());
        Files.write(Paths.get(a[2]), e.text().getBytes("UTF-8"));
    }
}
