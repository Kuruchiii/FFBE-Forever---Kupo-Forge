import com.ffbeforever.saveedit.*;
import java.nio.file.*;
public class N {
    public static void main(String[] a) throws Exception {
        Db db = new Db(new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8"));
        String in = new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8");
        Editor e = new Editor(db, in);
        System.out.println("name before: " + e.playerName());
        String[] tries = {"", "   ", "Rain\t", "A B", "x".repeat(65), "😀".repeat(64), "Kuruchi"};
        for (String t : tries) System.out.println("[" + t.length() + "] -> " + Editor.nameProblem(t));
        System.out.println("set: " + e.setPlayerName("Kuruchi ★ 魔") + " dirty=" + e.dirty + " check=" + e.check());
        Files.write(Paths.get(a[2]), e.text().getBytes("UTF-8"));
        Editor e2 = new Editor(db, e.text());
        System.out.println("unchanged name -> dirty " + (e2.setPlayerName(e2.playerName()) == null && !e2.dirty));
    }
}
