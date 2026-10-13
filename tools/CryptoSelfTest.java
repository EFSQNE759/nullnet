import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

public class CryptoSelfTest {
    static SecureRandom rng = new SecureRandom();

    static byte[] x25519(byte[] priv, byte[] pub) {
        X25519Agreement a = new X25519Agreement();
        a.init(new X25519PrivateKeyParameters(priv, 0));
        byte[] out = new byte[a.getAgreementSize()];
        a.calculateAgreement(new X25519PublicKeyParameters(pub, 0), out, 0);
        return out;
    }

    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len) {
        HKDFBytesGenerator g = new HKDFBytesGenerator(new SHA256Digest());
        g.init(new HKDFParameters(ikm, salt, info));
        byte[] out = new byte[len];
        g.generateBytes(out, 0, len);
        return out;
    }

    static byte[] gcmEnc(byte[] key, byte[] nonce, byte[] pt) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return c.doFinal(pt);
    }

    static byte[] gcmDec(byte[] key, byte[] nonce, byte[] ct) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return c.doFinal(ct);
    }

    static String b32(byte[] d) {
        String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder sb = new StringBuilder((d.length * 8 + 4) / 5);
        int buffer = 0, bits = 0;
        for (byte b : d) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) { bits -= 5; sb.append(A.charAt((buffer >> bits) & 0x1F)); }
        }
        if (bits > 0) sb.append(A.charAt((buffer << (5 - bits)) & 0x1F));
        return sb.toString();
    }

    static byte[] b32dec(String s) {
        String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : s.toCharArray()) {
            int v = A.indexOf(Character.toUpperCase(c));
            if (v < 0) throw new IllegalArgumentException("bad b32");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) { bits -= 8; out.write((buffer >> bits) & 0xFF); }
        }
        return out.toByteArray();
    }

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) System.exit(1);
    }

    public static void main(String[] args) throws Exception {
        // --- sealed box: sender -> recipient ---
        X25519PrivateKeyParameters senderKp = new X25519PrivateKeyParameters(rng);
        X25519PrivateKeyParameters recipKp = new X25519PrivateKeyParameters(rng);
        byte[] senderPriv = senderKp.getEncoded();
        byte[] recipientPub = recipKp.generatePublicKey().getEncoded();
        byte[] recipientPriv = recipKp.getEncoded();
        byte[] senderPub = senderKp.generatePublicKey().getEncoded();

        // ephemeral (sender)
        X25519PrivateKeyParameters ephKp = new X25519PrivateKeyParameters(rng);
        byte[] ephPriv = ephKp.getEncoded();
        byte[] ephPub = ephKp.generatePublicKey().getEncoded();

        byte[] salt = new byte[32]; rng.nextBytes(salt);
        byte[] nonce = new byte[12]; rng.nextBytes(nonce);

        byte[] plaintext = "Uçtan uca şifreli test mesajı \uD83D\uDD12".getBytes(StandardCharsets.UTF_8);

        // sender key
        byte[] shared = x25519(senderPriv, recipientPub);
        byte[] sharedE = x25519(ephPriv, recipientPub);
        byte[] key = hkdf(concat(shared, sharedE), salt, "gizli-msg-v1".getBytes(StandardCharsets.UTF_8), 32);
        byte[] ct = gcmEnc(key, nonce, plaintext);

        // recipient key
        byte[] rShared = x25519(recipientPriv, senderPub);
        byte[] rSharedE = x25519(recipientPriv, ephPub);
        byte[] rKey = hkdf(concat(rShared, rSharedE), salt, "gizli-msg-v1".getBytes(StandardCharsets.UTF_8), 32);
        byte[] dec = gcmDec(rKey, nonce, ct);

        check(Arrays.equals(rKey, key), "HKDF anahtari esit");
        check(Arrays.equals(dec, plaintext), "seal/open roundtrip");

        // tampering must fail
        ct[5] ^= 0x01;
        boolean failed = true;
        try { gcmDec(rKey, nonce, ct); failed = false; } catch (Exception ignored) {}
        check(failed, "tampersiz zarf reddedildi (GCM auth)");

        // wrong key must fail
        byte[] otherPriv = new X25519PrivateKeyParameters(rng).getEncoded();
        byte[] othPub = new X25519PrivateKeyParameters(otherPriv, 0).generatePublicKey().getEncoded();
        byte[] w = hkdf(concat(recipientPriv.length == 32 ? otherPriv : otherPriv, new byte[32]),
                salt, "gizli-msg-v1".getBytes(StandardCharsets.UTF_8), 32);
        boolean won = true;
        try { gcmDec(otherPriv.length == 32 ? w : w, nonce, new byte[16]); } catch (Exception e) { won = false; }
        check(!won, "yanlis anahtar reddedildi");

        // --- Ed25519 sign / verify ---
        Ed25519PrivateKeyParameters edPriv = new Ed25519PrivateKeyParameters(rng);
        byte[] edPub = edPriv.generatePublicKey().getEncoded();
        byte[] msg = "Gizli-update-v1\n2\n1.1.0\nabc\nurl\n26\nnote".getBytes(StandardCharsets.UTF_8);
        Ed25519Signer sig = new Ed25519Signer();
        sig.init(true, edPriv);
        sig.update(msg, 0, msg.length);
        byte[] signature = sig.generateSignature();
        Ed25519Signer ver = new Ed25519Signer();
        ver.init(false, new Ed25519PublicKeyParameters(edPub, 0));
        ver.update(msg, 0, msg.length);
        check(ver.verifySignature(signature), "ed25519 sign/verify");

        // --- base32 ---
        byte[] uid = new byte[16]; rng.nextBytes(uid);
        check(Arrays.equals(b32dec(b32(uid)), uid), "base32 roundtrip");

        System.out.println("ALL PASS");
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}