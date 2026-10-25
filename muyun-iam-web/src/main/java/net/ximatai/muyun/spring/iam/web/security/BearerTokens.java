package net.ximatai.muyun.spring.iam.web.security;

import jakarta.servlet.http.HttpServletRequest;

/** Reads the platform session token from the {@code Authorization: Bearer} request header. */
public final class BearerTokens {
    private static final String PREFIX = "Bearer ";

    private BearerTokens() {
    }

    /** The trimmed token, or {@code null} when the header is absent or not a Bearer credential. */
    public static String from(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank() || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return null;
        }
        return header.substring(PREFIX.length()).trim();
    }
}
