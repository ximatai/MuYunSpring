package net.ximatai.muyun.spring.ability.security;

import net.ximatai.muyun.spring.common.security.FieldProtectionException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM implementation for the platform field-protection contract. */
public final class AesGcmFieldCryptoProvider implements FieldCryptoProvider {
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmFieldCryptoProvider(byte[] keyBytes) {
        if (keyBytes == null || (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32)) {
            throw new IllegalArgumentException("AES field-protection key must contain 16, 24, or 32 bytes");
        }
        this.key = new SecretKeySpec(keyBytes.clone(), "AES");
    }

    @Override
    public String encrypt(String fieldName, Object plainValue) {
        if (plainValue == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(fieldName));
            byte[] encrypted = cipher.doFinal(String.valueOf(plainValue).getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (Exception exception) {
            throw new FieldProtectionException("cannot encrypt protected field: " + fieldName, exception);
        }
    }

    @Override
    public Object decrypt(String fieldName, String protectedValue) {
        if (protectedValue == null) return null;
        if (!protectedValue.startsWith(PREFIX)) {
            throw new FieldProtectionException("unsupported protected field value: " + fieldName);
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(protectedValue.substring(PREFIX.length()));
            if (payload.length <= IV_BYTES) {
                throw new FieldProtectionException("invalid protected field value: " + fieldName);
            }
            byte[] iv = java.util.Arrays.copyOfRange(payload, 0, IV_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(payload, IV_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(fieldName));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (FieldProtectionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new FieldProtectionException("cannot decrypt protected field: " + fieldName, exception);
        }
    }

    private byte[] aad(String fieldName) {
        return (fieldName == null ? "" : fieldName).getBytes(StandardCharsets.UTF_8);
    }
}
