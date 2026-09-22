package net.ximatai.muyun.spring.common.security;

/** Automatic assistant projection of a field; never grants ordinary read or write permission. */
public enum AssistantFieldPolicy {
    HIDDEN, DESCRIBE, READ, READ_WRITE
}
