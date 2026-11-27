import com.ffbeforever.saveedit.V2Signer;
import java.io.File;
import java.io.FileInputStream;

/** usage: SignApk in.apk out.apk key.pk8 cert.crt */
public class SignApk {
    public static void main(String[] a) throws Exception {
        new V2Signer(new FileInputStream(a[2]), new FileInputStream(a[3])).sign(new File(a[0]), new File(a[1]));
    }
}
