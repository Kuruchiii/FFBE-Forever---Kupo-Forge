import com.ffbeforever.saveedit.*;
import java.nio.file.*;
public class R { public static void main(String[] a) throws Exception {
 String in = new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8");
 String out = Json.write(Json.parse(in)) + "\n";
 int i=0; while (i<Math.min(in.length(),out.length()) && in.charAt(i)==out.charAt(i)) i++;
 System.out.println("len "+in.length()+" "+out.length()+" diff at "+i);
 System.out.println("IN : "+in.substring(Math.max(0,i-80), Math.min(in.length(), i+80)).replace("\n","\\n"));
 System.out.println("OUT: "+out.substring(Math.max(0,i-80), Math.min(out.length(), i+80)).replace("\n","\\n"));
}}
