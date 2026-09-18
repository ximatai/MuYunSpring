package net.ximatai.muyun.spring.ability.security;

import net.ximatai.muyun.spring.common.security.FieldProtectionException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** HMAC-SHA-256 signer for protected database fields. */
public final class HmacSha256FieldSigner implements FieldSigner {
    private final SecretKeySpec key;

    public HmacSha256FieldSigner(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length < 16) {
            throw new IllegalArgumentException("field-signing key must contain at least 16 bytes");
        }
        this.key = new SecretKeySpec(keyBytes.clone(), "HmacSHA256");
    }

    @Override
    public String sign(String fieldName, Object plainValue) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update((fieldName == null ? "" : fieldName).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(String.valueOf(plainValue).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (Exception exception) {
            throw new FieldProtectionException("cannot sign protected field: " + fieldName, exception);
        }
    }
}
