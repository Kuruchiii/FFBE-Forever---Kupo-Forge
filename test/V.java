public class V { public static void main(String[] a) throws Exception {
  com.android.apksig.ApkVerifier.Result r = new com.android.apksig.ApkVerifier.Builder(new java.io.File(a[0])).build().verify();
  System.out.println("v2=" + r.isVerifiedUsingV2Scheme() + " ok=" + r.isVerified() + " errors=" + r.getErrors() + " warnings=" + r.getWarnings());
}}
