package net.ximatai.muyun.spring.platform.job;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** Length prefixes distinguish separators and platform null from every tenant identity. */
public final class JobKeys {
    private JobKeys() { }
    public static String effective(String type, JobScope scope, String tenant, String topic) {
        if (scope == JobScope.PLATFORM && tenant != null || scope == JobScope.TENANT && (tenant == null || tenant.isBlank()))
            throw new IllegalArgumentException("Job scope and tenant disagree");
        if (type == null || type.isBlank() || topic == null || topic.length() > 1024) throw new IllegalArgumentException("Invalid job key");
        String input = part(type) + part(scope.name()) + part(tenant) + part(topic);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String part(String value) { return value == null ? "-1:" : value.length() + ":" + value; }
}
