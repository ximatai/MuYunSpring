package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.i18n.TextReference;

/** Immutable regex rule inside an effective password complexity snapshot. */
public record EffectivePasswordRule(String id, String tenantId, String pattern, TextReference message) {
    public EffectivePasswordRule(String pattern, TextReference message) {
        this(null, null, pattern, message);
    }
    public EffectivePasswordRule {
        if (pattern == null) {
            throw new IllegalArgumentException("effective password rule requires a pattern");
        }
        if (message == null) {
            throw new IllegalArgumentException("effective password rule requires a message");
        }
    }
}
