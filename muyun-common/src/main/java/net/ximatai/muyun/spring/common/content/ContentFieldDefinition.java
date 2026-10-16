package net.ximatai.muyun.spring.common.content;

import java.util.Objects;

/** Same compiled field contract for static declarations and dynamic metadata. */
public record ContentFieldDefinition(String key, ContentPolicy policy) {
    public static final ContentFieldDefinition NONE = new ContentFieldDefinition(null, ContentPolicy.KEEP_ORIGINAL);

    public ContentFieldDefinition {
        policy = Objects.requireNonNullElse(policy, ContentPolicy.KEEP_ORIGINAL);
        if (policy.enabled() && (key == null || key.isBlank() || key.length() > 128))
            throw new IllegalArgumentException("Business content requires a stable field key of at most 128 characters");
    }

    public boolean enabled() { return policy.enabled(); }
}
