package net.ximatai.muyun.spring.iam.welink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.iam.federation.ExternalIdentityVerificationException;
import net.ximatai.muyun.spring.iam.federation.IdentityProviderDescriptor;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Vendor-only HTTP, pacing and application-token cache. No request/response or upstream error logging. */
public final class WeLinkClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;
    private final Clock clock;
    private final Map<String, Slot> slots = new LinkedHashMap<>(16, .75f, true);
    private static final class Slot {
        final ReentrantLock lock = new ReentrantLock();
        String token;
        Instant refreshAt = Instant.MIN;
        long nextRequestNanos;
    }
    public WeLinkClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }
    WeLinkClient(HttpClient http, Clock clock) { this.http = http; this.clock = clock; }

    private synchronized Slot slot(IdentityProviderDescriptor provider) {
        // A changed credential or authority must never reuse a former application's ticket.
        String key = cacheKey(provider);
        Slot slot = slots.computeIfAbsent(key, unused -> new Slot());
        if (slots.size() > 256) slots.remove(slots.keySet().iterator().next());
        return slot;
    }
    static String cacheKey(IdentityProviderDescriptor provider) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(List.of(provider.id(), Objects.toString(provider.clientId(), ""),
                    Objects.toString(provider.clientSecret(), ""), new TreeMap<>(provider.configuration())));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) { throw unavailable(); }
    }
    JsonNode exchange(IdentityProviderDescriptor provider, Map<String, String> parameters) {
        var config = new WeLinkConfiguration(provider.configuration());
        String encoded = form(parameters);
        boolean query = config.tokenMode().equals("query");
        URI uri = config.api("/api/oauth2/v1/token");
        if (query) uri = URI.create(uri + "?" + encoded);
        // Authorization-code exchange is never automatically retried: consumption may have succeeded.
        return request(provider, config, slot(provider), uri, "POST", query ? "" : encoded,
                "application/x-www-form-urlencoded", null, false);
    }
    JsonNode identity(IdentityProviderDescriptor provider, String token) {
        var config = new WeLinkConfiguration(provider.configuration());
        return request(provider, config, slot(provider), config.api("/api/auth/v1/userid"),
                "POST", "", "application/json", token, false);
    }
    JsonNode directory(IdentityProviderDescriptor provider, String path, Map<String, String> parameters) {
        var config = new WeLinkConfiguration(provider.configuration());
        Slot slot = slot(provider);
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = ticket(provider, config, slot);
            JsonNode result;
            try {
                result = request(provider, config, slot, URI.create(config.api(path) + "?" + form(parameters)),
                        "GET", null, null, token, true);
            } catch (AuthenticationFailure failure) {
                slot.lock.lock();
                try { if (Objects.equals(slot.token, token)) slot.token = null; }
                finally { slot.lock.unlock(); }
                if (attempt == 0) continue;
                throw rejected();
            }
            if (authenticationFailure(config, result.path("code").asText())) {
                slot.lock.lock();
                try { if (Objects.equals(slot.token, token)) slot.token = null; }
                finally { slot.lock.unlock(); }
                if (attempt == 0) continue;
                throw rejected();
            }
            success(result);
            return result;
        }
        throw rejected();
    }
    private String ticket(IdentityProviderDescriptor provider, WeLinkConfiguration config, Slot slot) {
        slot.lock.lock();
        try {
            if (slot.token != null && clock.instant().isBefore(slot.refreshAt)) return slot.token;
            credentials(provider);
            String body;
            try { body = JSON.writeValueAsString(Map.of("client_id", provider.clientId(), "client_secret", provider.clientSecret())); }
            catch (Exception failure) { throw unavailable(); }
            Instant started = clock.instant();
            JsonNode result = request(provider, config, slot, config.api("/api/auth/v2/tickets"),
                    "POST", body, "application/json", null, true);
            success(result);
            String token = required(result, "access_token");
            long lifetime = result.path("expires_in").asLong(0);
            if (lifetime <= 0 || lifetime > 604800) throw unavailable();
            // Short-lived test/vendor tickets retain useful cache lifetime rather than refresh on every call.
            long ahead = Math.min(config.number("refreshAheadSeconds", 300, 0, 3600), lifetime / 2);
            slot.token = token;
            slot.refreshAt = started.plusSeconds(lifetime - ahead);
            return token;
        } finally { slot.lock.unlock(); }
    }
    private JsonNode request(IdentityProviderDescriptor provider, WeLinkConfiguration config, Slot slot,
                             URI uri, String method, String body, String contentType, String token, boolean retryRateLimit) {
        int retries = retryRateLimit ? config.number("rateLimitRetries", 2, 0, 5) : 0;
        for (int attempt = 0; ; attempt++) {
            pace(slot, config.number("minRequestIntervalMillis", 0, 0, 60000));
            try {
                var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(config.timeout()))
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
                if (contentType != null) builder.header("Content-Type", contentType);
                if (token != null) builder.header("x-wlk-Authorization", token);
                var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                JsonNode result = null;
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    result = JSON.readTree(response.body());
                    if (result == null || !result.isObject()) throw unavailable();
                }
                boolean limited = response.statusCode() == 429 || (result != null
                        && config.csv("rateLimitCodes", "").contains(result.path("code").asText()));
                if (limited && attempt < retries) {
                    long delay = Math.min(60000L, (long) config.number("backoffMillis", 1000, 0, 60000) << attempt);
                    String retryAfter = response.headers().firstValue("Retry-After").orElse("");
                    try { delay = Math.max(delay, Math.min(60, Math.max(0, Long.parseLong(retryAfter))) * 1000); }
                    catch (NumberFormatException ignored) { /* Bounded exponential fallback for HTTP-date values. */ }
                    pause(delay);
                    continue;
                }
                if (limited) throw unavailable();
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    if (token != null && retryRateLimit) throw new AuthenticationFailure();
                    throw rejected();
                }
                if (result == null) throw unavailable();
                return result;
            } catch (AuthenticationFailure failure) { throw failure; }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw unavailable();
            } catch (ExternalIdentityVerificationException failure) { throw failure; }
            catch (Exception failure) {
                // Do not attach the HTTP/Jackson exception: it may include query credentials or response content.
                throw unavailable();
            }
        }
    }
    private static boolean authenticationFailure(WeLinkConfiguration config, String code) {
        return config.csv("authenticationFailureCodes", "401,403").contains(code);
    }
    private static void pace(Slot slot, int interval) {
        slot.lock.lock();
        try {
            long remaining = slot.nextRequestNanos - System.nanoTime();
            if (remaining > 0) pause((remaining + 999999) / 1000000);
            slot.nextRequestNanos = System.nanoTime() + interval * 1000000L;
        } finally { slot.lock.unlock(); }
    }
    private static void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw unavailable(); }
    }
    static void credentials(IdentityProviderDescriptor p) {
        if (p.clientId() == null || p.clientId().isBlank() || p.clientSecret() == null || p.clientSecret().isBlank()) throw rejected();
    }
    static void success(JsonNode result) { if (!"0".equals(result.path("code").asText())) throw rejected(); }
    static String required(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isValueNode() || value.isNull() || value.asText().isBlank()) throw unavailable();
        return value.asText();
    }
    static String form(Map<String, String> parameters) {
        return parameters.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).reduce((a, b) -> a + "&" + b).orElse("");
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static ExternalIdentityVerificationException rejected() { return ExternalIdentityVerificationException.rejected("WeLink request rejected"); }
    static ExternalIdentityVerificationException unavailable() { return ExternalIdentityVerificationException.unavailable("WeLink service unavailable or invalid response", null); }
    private static final class AuthenticationFailure extends RuntimeException { }
}
