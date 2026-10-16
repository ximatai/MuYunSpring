package net.ximatai.muyun.spring.common.i18n;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Original presentation text and its stable source identity; never a business record value. */
public record TextReference(SourceKind sourceKind, String sourceId, String sourceVersion, String originalText) {
    public enum SourceKind { CODE, CONFIG }

    public TextReference {
        Objects.requireNonNull(sourceKind, "sourceKind");
        sourceId = requireIdentifier(sourceId, "sourceId");
        sourceVersion = requireIdentifier(sourceVersion, "sourceVersion");
        Objects.requireNonNull(originalText, "originalText");
    }

    public static TextReference code(String key, String originalText) {
        return new TextReference(SourceKind.CODE, key, versionOf(originalText), originalText);
    }

    public static TextReference configured(String identity, String originalText) {
        return new TextReference(SourceKind.CONFIG, identity, versionOf(originalText), originalText);
    }

    /** Frozen history reads only this original's deployed versioned resource, never a newer plain-key template. */
    public static TextReference frozenCode(String key, String originalText) {
        return code(key + "@" + versionOf(originalText), originalText);
    }

    /** Content revisions remain stable when only unrelated structure or business rules change. */
    public static String versionOf(String text) {
        try {
            return "sha256-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(text, "originalText").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
