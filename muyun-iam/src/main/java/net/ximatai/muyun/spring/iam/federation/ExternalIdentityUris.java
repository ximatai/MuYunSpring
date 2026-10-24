package net.ximatai.muyun.spring.iam.federation;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Builds absolute addresses below configured bases without string concatenation mistakes. */
public final class ExternalIdentityUris {
    private ExternalIdentityUris() {
    }

    /** Resolves a relative path below a base address, whether or not the base ends with a slash. */
    public static URI resolve(URI base, String relativePath) {
        String text = base.toString();
        return URI.create(text.endsWith("/") ? text : text + "/").resolve(relativePath);
    }

    /** Appends query parameters in a stable order, preserving parameters already on the address. */
    public static URI withQuery(URI base, Map<String, String> parameters) {
        String query = new TreeMap<>(parameters).entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        String text = base.toString();
        return URI.create(text + (text.contains("?") ? "&" : "?") + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
