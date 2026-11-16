package com.snapsave.app.util;

import android.content.Context;
import android.security.KeyPairGeneratorSpec;
import android.util.Base64;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Calendar;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

/**
 * API 21+ credential envelopes: a Keystore RSA key wraps a fresh AES-128 key per value.
 * AES-GCM authenticates both the value and its purpose (including the resolver origin).
 * No plaintext fallback, exported private key, logging, or third-party dependencies.
 */
final class SecureCredentials {
    private static final String ALIAS = "snapsave.credentials.rsa.v1";
    private static final String ERROR = "Secure credential storage is unavailable or its data cannot be read. "
            + "Unlock this device and retry, or clear and re-enter the affected credential.";

    private SecureCredentials() {
    }

    static String seal(Context context, String purpose, String value) {
        byte[] key = new byte[16];
        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        try {
            SecureRandom random = new SecureRandom();
            random.nextBytes(key);
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            aes.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = aes.doFinal(plain);

            KeyStore store = store();
            if (!store.containsAlias(ALIAS)) {
                Calendar start = Calendar.getInstance();
                Calendar end = Calendar.getInstance();
                end.add(Calendar.YEAR, 25);
                // KeyPairGeneratorSpec is available before API 21, unlike KeyGenParameterSpec.
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
                generator.initialize(new KeyPairGeneratorSpec.Builder(context)
                        .setAlias(ALIAS)
                        .setKeySize(2048)
                        .setSubject(new X500Principal("CN=SnapSave credentials"))
                        .setSerialNumber(BigInteger.ONE)
                        .setStartDate(start.getTime())
                        .setEndDate(end.getTime())
                        .build());
                generator.generateKeyPair();
                store = store();
            }
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, store.getCertificate(ALIAS).getPublicKey());
            String envelope = "1:" + encode(rsa.doFinal(key)) + ":" + encode(iv) + ":" + encode(encrypted);
            // Verify this device can decrypt before a caller removes any legacy plaintext.
            if (!value.equals(open(purpose, envelope))) {
                throw new IllegalStateException(ERROR);
            }
            return envelope;
        } catch (Exception e) {
            // Provider exceptions can contain input data. Never expose them to UI or logs.
            throw new IllegalStateException(ERROR);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(plain, (byte) 0);
        }
    }

    static String open(String purpose, String envelope) {
        byte[] key = null;
        byte[] plain = null;
        try {
            String[] parts = envelope.split(":", -1);
            if (parts.length != 4 || !"1".equals(parts[0])) {
                throw new IllegalStateException(ERROR);
            }
            byte[] iv = Base64.decode(parts[2], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[3], Base64.NO_WRAP);
            if (iv.length != 12 || encrypted.length < 16) {
                throw new IllegalStateException(ERROR);
            }
            KeyStore store = store();
            // A lost/invalidated key is an error; never generate a replacement during a read.
            PrivateKey privateKey = (PrivateKey) store.getKey(ALIAS, null);
            if (privateKey == null) {
                throw new IllegalStateException(ERROR);
            }
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.DECRYPT_MODE, privateKey);
            key = rsa.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
            if (key.length != 16) {
                throw new IllegalStateException(ERROR);
            }
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            aes.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            plain = aes.doFinal(encrypted);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(ERROR);
        } finally {
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    private static KeyStore store() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }

    private static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
