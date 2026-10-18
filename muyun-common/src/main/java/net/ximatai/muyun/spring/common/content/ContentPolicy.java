package net.ximatai.muyun.spring.common.content;

/** Explicit business-value policy, independent of UI/configuration localization. */
public enum ContentPolicy {
    KEEP_ORIGINAL, AUTO_TRANSLATE, MANUAL_TRANSLATIONS;

    public boolean enabled() { return this != KEEP_ORIGINAL; }
}
