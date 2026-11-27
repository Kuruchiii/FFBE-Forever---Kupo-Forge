import com.ffbeforever.saveedit.*;
import java.nio.file.*;
public class I { public static void main(String[] a) throws Exception {
  Db db = new Db(new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8"));
  int t=0,b=0; java.util.Map<String,Integer> slots=new java.util.TreeMap<String,Integer>();
  for (Db.Item it : db.items) { if (it.tmr) t++; if (it.banner>=0) b++; slots.merge(it.slot,1,Integer::sum); }
  System.out.println("items "+db.items.size()+" tmr "+t+" with banner "+b+" banners "+db.banners.size()+" "+slots);
  for (Db.Item it : db.items) if (it.name.contains("(TMR: Golbez)")||it.name.contains("(TMR: Cecil)")) System.out.println(it.name+" "+it.slot+"/"+it.type+" "+db.banners.get(it.banner).label());
}}
