import com.ffbeforever.saveedit.*;
import java.nio.file.*;
import java.util.*;
public class T {
    public static void main(String[] a) throws Exception {
        Db db = new Db(new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8"));
        String in = new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8");
        Editor e = new Editor(db, in);
        System.out.println("roundtrip identical: " + e.text().equals(in));
        int lv=0, st=0, tm=0;
        for (Map<String,Object> u : e.roster) {
            if (e.setStars(u, 7) > 0) st++;
            if (e.maxLevel(u, true)) lv++;
            if (e.tmr(u) == Editor.TMR_DONE) tm++;
        }
        e.grant(22, 504020430L, 2);
        e.grant(21, 403041100L, 1);
        System.out.println("stars " + st + " level " + lv + " tmr " + tm + " problems " + e.check());
        Files.write(Paths.get(a[2]), e.text().getBytes("UTF-8"));
        for (int i = 0; i < 5; i++) System.out.println(e.label(e.roster.get(i)));
    }
}
