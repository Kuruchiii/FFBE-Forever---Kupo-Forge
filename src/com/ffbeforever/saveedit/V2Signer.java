package com.ffbeforever.saveedit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.KeyFactory;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;

/**
 * APK Signature Scheme v2 signer (RSA PKCS#1 v1.5 + SHA-256, algorithm 0x0103), enough for
 * minSdk 24+ apps. Takes an unsigned zip and writes the signing block before its central directory.
 */
public final class V2Signer {
    private static final int CHUNK = 1 << 20;
    private static final int V2_ID = 0x7109871a;
    private static final int ALG = 0x0103;

    private final PrivateKey key;
    private final X509Certificate cert;

    /** key = PKCS#8 DER private key, crt = X.509 DER certificate. */
    public V2Signer(InputStream keyDer, InputStream crtDer) throws Exception {
        byte[] k = readAll(keyDer);
        key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(k));
        cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(crtDer);
        crtDer.close();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toByteArray();
    }

    public String certSha256() throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02X", x & 0xff));
        return b.toString();
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
    private static long u32(byte[] b, int o) { return u16(b, o) | ((long) u16(b, o + 2)) << 16; }
    private static void le32(ByteArrayOutputStream o, long v) {
        o.write((int) v); o.write((int) (v >> 8)); o.write((int) (v >> 16)); o.write((int) (v >> 24));
    }
    private static void le64(ByteArrayOutputStream o, long v) { le32(o, v & 0xffffffffL); le32(o, v >>> 32); }
    private static byte[] lp(byte[] b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length + 4);
        le32(o, b.length);
        o.write(b, 0, b.length);
        return o.toByteArray();
    }
    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    /** Chunked SHA-256 over the given sections (v2 "content digest"). */
    private static byte[] digest(RandomAccessFile f, long[][] ranges, byte[] eocd) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long total = 0;
        for (long[] r : ranges) total += (r[1] + CHUNK - 1) / CHUNK;
        total += (eocd.length + CHUNK - 1) / CHUNK;
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(0x5a);
        le32(all, total);
        byte[] buf = new byte[CHUNK];
        byte[] pre = new byte[5];
        pre[0] = (byte) 0xa5;
        for (long[] r : ranges) {
            long pos = r[0], left = r[1];
            while (left > 0) {
                int n = (int) Math.min(CHUNK, left);
                f.seek(pos);
                f.readFully(buf, 0, n);
                md.reset();
                pre[1] = (byte) n; pre[2] = (byte) (n >> 8); pre[3] = (byte) (n >> 16); pre[4] = (byte) (n >> 24);
                md.update(pre);
                md.update(buf, 0, n);
                byte[] d = md.digest();
                all.write(d, 0, d.length);
                pos += n;
                left -= n;
            }
        }
        for (int off = 0; off < eocd.length; off += CHUNK) {
            int n = Math.min(CHUNK, eocd.length - off);
            md.reset();
            pre[1] = (byte) n; pre[2] = (byte) (n >> 8); pre[3] = (byte) (n >> 16); pre[4] = (byte) (n >> 24);
            md.update(pre);
            md.update(eocd, off, n);
            byte[] d = md.digest();
            all.write(d, 0, d.length);
        }
        md.reset();
        return md.digest(all.toByteArray());
    }

    /** Signs in -> out. "in" must be a plain zip without an APK signing block or zip comment. */
    public void sign(File in, File out) throws Exception {
        RandomAccessFile f = new RandomAccessFile(in, "r");
        try {
            long len = f.length();
            byte[] eocd = new byte[22];
            f.seek(len - 22);
            f.readFully(eocd);
            if (u32(eocd, 0) != 0x06054b50L) throw new IOException("Zip must end with a plain EOCD (no comment)");
            long cdOff = u32(eocd, 16), cdSize = u32(eocd, 12);
            if (cdOff + cdSize != len - 22) throw new IOException("Unexpected zip layout");
            byte[] dig = digest(f, new long[][]{{0, cdOff}, {cdOff, cdSize}}, eocd);

            byte[] digests = lp(lp(cat(le(ALG), lp(dig))));
            byte[] certs = lp(lp(cert.getEncoded()));
            byte[] attrs = lp(new byte[0]);
            byte[] signedData = cat(digests, certs, attrs);
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update(signedData);
            byte[] sig = s.sign();
            byte[] sigs = lp(lp(cat(le(ALG), lp(sig))));
            byte[] signer = lp(cat(lp(signedData), sigs, lp(cert.getPublicKey().getEncoded())));
            byte[] v2 = lp(signer);

            ByteArrayOutputStream pair = new ByteArrayOutputStream();
            le64(pair, 4 + v2.length);
            le32(pair, V2_ID);
            pair.write(v2, 0, v2.length);
            byte[] pairs = pair.toByteArray();
            long blockSize = pairs.length + 8 + 16;
            ByteArrayOutputStream blk = new ByteArrayOutputStream();
            le64(blk, blockSize);
            blk.write(pairs, 0, pairs.length);
            le64(blk, blockSize);
            byte[] magic = "APK Sig Block 42".getBytes("US-ASCII");
            blk.write(magic, 0, magic.length);
            byte[] block = blk.toByteArray();

            RandomAccessFile o = new RandomAccessFile(out, "rw");
            try {
                o.setLength(0);
                byte[] buf = new byte[1 << 16];
                f.seek(0);
                long left = cdOff + cdSize;
                long copied = 0;
                while (copied < cdOff) {
                    int n = f.read(buf, 0, (int) Math.min(buf.length, cdOff - copied));
                    o.write(buf, 0, n);
                    copied += n;
                }
                o.write(block);
                left = cdSize;
                while (left > 0) {
                    int n = f.read(buf, 0, (int) Math.min(buf.length, left));
                    o.write(buf, 0, n);
                    left -= n;
                }
                long newCd = cdOff + block.length;
                eocd[16] = (byte) newCd; eocd[17] = (byte) (newCd >> 8);
                eocd[18] = (byte) (newCd >> 16); eocd[19] = (byte) (newCd >> 24);
                o.write(eocd);
            } finally {
                o.close();
            }
        } finally {
            f.close();
        }
    }

    private static byte[] le(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }
}
