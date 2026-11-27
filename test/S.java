import com.ffbeforever.saveedit.*;
import java.io.*;
public class S { public static void main(String[] a) throws Exception {
  new V2Signer(new FileInputStream(a[2]+".pk8"), new FileInputStream(a[2]+".crt")).sign(new File(a[0]), new File(a[1]));
}}
