import com.ffbeforever.saveedit.*;
import java.io.*;
import java.nio.file.*;
public class P { public static void main(String[] a) throws Exception {
  byte[] hook = Files.readAllBytes(Paths.get(a[1]));
  Patcher.Log log = new Patcher.Log(){ public void line(String s){ System.out.println(s);} };
  V2Signer sg = new V2Signer(new FileInputStream(a[3]+".pk8"), new FileInputStream(a[3]+".crt"));
  System.out.println("cert " + sg.certSha256());
  Patcher.patch(new File(a[0]), new File(a[2]), hook, sg, log);
  System.out.println("patched? " + Patcher.isPatched(new File(a[2])));
  Patcher.patch(new File(a[2]), new File(a[2] + ".2.apk"), hook, sg, log);
  for (String f : new String[]{a[2], a[2] + ".2.apk"}) {
    com.android.apksig.ApkVerifier.Result r = new com.android.apksig.ApkVerifier.Builder(new File(f)).build().verify();
    System.out.println(f.substring(f.length()-12) + " verify v2=" + r.isVerifiedUsingV2Scheme() + " ok=" + r.isVerified() + " errors=" + r.getErrors() + " warn=" + r.getWarnings());
  }
}}
