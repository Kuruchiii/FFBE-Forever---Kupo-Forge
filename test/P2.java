import com.ffbeforever.saveedit.*;
import java.io.*;
import java.nio.file.*;
public class P2 { public static void main(String[] a) throws Exception {
  byte[] hook = Files.readAllBytes(Paths.get(a[1]));
  Patcher.Extra x = new Patcher.Extra();
  x.dex7 = Files.readAllBytes(Paths.get(a[3] + "/classes7.dex"));
  x.dex8 = Files.readAllBytes(Paths.get(a[3] + "/classes8.dex"));
  x.origSha = new String(Files.readAllBytes(Paths.get(a[3] + "/classes7.sha256"))).trim();
  Patcher.Log log = new Patcher.Log(){ public void line(String s){ System.out.println(s);} };
  V2Signer sg = new V2Signer(new FileInputStream(a[4]+".pk8"), new FileInputStream(a[4]+".crt"));
  Patcher.patch(new File(a[0]), new File(a[2]), hook, x, sg, log);
  Patcher.patch(new File(a[2]), new File(a[2] + ".2.apk"), hook, x, sg, log);
  for (String f : new String[]{a[2], a[2] + ".2.apk"}) {
    com.android.apksig.ApkVerifier.Result r = new com.android.apksig.ApkVerifier.Builder(new File(f)).build().verify();
    System.out.println(f.substring(f.length()-12) + " verify v2=" + r.isVerifiedUsingV2Scheme() + " ok=" + r.isVerified() + " errors=" + r.getErrors());
  }
}}
