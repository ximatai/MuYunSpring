package net.ximatai.muyun.spring.starter.configuration.filetransfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.attachment.FileReceptionClient;
import net.ximatai.muyun.spring.platform.attachment.FileReceptionException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

import static net.ximatai.muyun.spring.platform.attachment.FileReceptionException.Kind.*;

/** Deployment-configured service credentials stay inside this transport adapter. */
final class MuYunFileServerReceptionClient implements FileReceptionClient, AutoCloseable {
    private final String baseUrl;
    private final String token;
    private final String systemScopeId;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final ObjectMapper json;
    private final HttpClient http;

    MuYunFileServerReceptionClient(MuYunFileServerTransferProperties properties, ObjectMapper json) {
        var reception = properties.getReception();
        URI base = reception != null && reception.getBaseUrl() != null
                ? reception.getBaseUrl() : properties.getBaseUrl();
        if (base == null || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null
                || !("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || reception == null || reception.getServiceToken() == null
                || !reception.getServiceToken().matches("[\\x21-\\x7E]{24,4096}")
                || reception.getRequestTimeout() == null || reception.getRequestTimeout().toMillis() < 1
                || reception.getRequestTimeout().compareTo(Duration.ofMinutes(30)) > 0
                || reception.getMaxResponseBytes() < 1 || reception.getMaxResponseBytes() > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("invalid file reception deployment configuration");
        }
        baseUrl = base.toString().replaceAll("/+$", "") + "/api/v1/internal/receptions";
        token = reception.getServiceToken();
        timeout = reception.getRequestTimeout();
        maxResponseBytes = reception.getMaxResponseBytes();
        systemScopeId = properties.getSystemScopeId();
        this.json = json;
        http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public void close() { http.close(); }

    @Override public Snapshot create(Create request) {
        Objects.requireNonNull(request, "request");
        Snapshot result = snapshot(call("POST", "", body(request), "application/json"), null);
        if (result.maxObjects() != request.maxObjects() || result.maxBytes() != request.maxBytes()) throw protocol();
        return result;
    }

    @Override public Snapshot get(String receptionId) {
        return snapshot(call("GET", route(receptionId), HttpRequest.BodyPublishers.noBody(), null), receptionId);
    }

    @Override public List<StagedObject> objects(String receptionId) {
        JsonNode data = call("GET", route(receptionId) + "/objects", HttpRequest.BodyPublishers.noBody(), null);
        if (!data.isArray()) throw protocol();
        try {
            var result = new ArrayList<StagedObject>();
            var keys = new HashSet<String>();
            for (JsonNode item : data) {
                String key = text(item, "key");
                if (!keys.add(key)) throw protocol();
                result.add(new StagedObject(key, number(item, "sizeBytes")));
            }
            return List.copyOf(result);
        } catch (IllegalArgumentException invalid) { throw protocol(); }
    }

    @Override public void put(String receptionId, String key, Path content) {
        String path = route(receptionId);
        FileReceptionClient.requireObjectKey(key);
        HttpRequest.BodyPublisher publisher;
        try { publisher = HttpRequest.BodyPublishers.ofFile(content); }
        catch (java.io.FileNotFoundException invalid) { throw new IllegalArgumentException("reception source file is unavailable"); }
        JsonNode result = call("PUT", path + "/objects/" + key, publisher, "application/octet-stream");
        if (!result.path("accepted").isBoolean() || !result.path("accepted").booleanValue()) throw protocol();
    }

    @Override public Snapshot confirm(String receptionId, List<ObjectEntry> objects) {
        String path = route(receptionId);
        List<ObjectEntry> entries = List.copyOf(objects);
        if (entries.isEmpty() || entries.stream().map(ObjectEntry::key).distinct().count() != entries.size()) {
            throw new IllegalArgumentException("invalid reception manifest");
        }
        Snapshot result = snapshot(call("POST", path + "/confirm", body(Map.of("objects", entries)), "application/json"), receptionId);
        if (result.state() != State.READY || !result.assets().keySet().equals(
                entries.stream().map(ObjectEntry::key).collect(java.util.stream.Collectors.toSet()))) throw protocol();
        return result;
    }

    @Override public Snapshot cancel(String receptionId) {
        Snapshot result = snapshot(call("POST", route(receptionId) + "/cancel", HttpRequest.BodyPublishers.noBody(), null), receptionId);
        if (result.state() != State.CANCELLED || !result.assets().isEmpty()) throw protocol();
        return result;
    }

    private HttpRequest.BodyPublisher body(Object body) {
        try { return HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("invalid reception request body"); }
    }

    private JsonNode call(String method, String path, HttpRequest.BodyPublisher body, String contentType) {
        var user = CurrentUserContext.currentUser().orElseThrow(
                () -> new IllegalStateException("file reception requires an authenticated identity"));
        String tenant = MuYunFileServerStorageScope.resolve(user, systemScopeId);
        if (!user.userId().matches("[\\x21-\\x7E]{1,256}")) {
            throw new IllegalStateException("file reception requires a valid storage identity");
        }
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                .header("Authorization", "Bearer " + token).header("X-Tenant-Id", tenant)
                .header("X-User-Id", user.userId()).method(method, body);
        if (contentType != null) request.header("Content-Type", contentType);
        // Includes complete body consumption, unlike a timeout that ends at response headers.
        var future = http.sendAsync(request.build(), ignored -> new BoundedBody(maxResponseBytes));
        try {
            var response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() / 100 != 2) throw new FileReceptionException(HTTP, response.statusCode());
            JsonNode envelope = json.readTree(response.body());
            JsonNode data = envelope == null ? null : envelope.get("data");
            if (data == null || data.isNull()) throw protocol();
            return data;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new FileReceptionException(TRANSPORT, null);
        } catch (ExecutionException | TimeoutException failed) {
            throw new FileReceptionException(TRANSPORT, null);
        } catch (java.io.IOException invalid) {
            throw protocol();
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    private Snapshot snapshot(JsonNode data, String expectedId) {
        try {
            String id = text(data, "id");
            route(id);
            if (expectedId != null && !expectedId.equals(id)) throw protocol();
            State state = State.valueOf(text(data, "state"));
            String bucket = text(data, "bucket");
            String prefix = text(data, "prefix");
            if (bucket.isBlank() || !prefix.equals("receiving/" + id.replace("-", "") + "/")) throw protocol();
            long expires = number(data, "expiresAt");
            long count = number(data, "maxObjects"), bytes = number(data, "maxBytes");
            if (expires <= 0 || count < 1 || count > Integer.MAX_VALUE || bytes < 1 || !data.path("assets").isObject()) throw protocol();
            var assets = new LinkedHashMap<String, String>();
            var fields = data.path("assets").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                FileReceptionClient.requireObjectKey(field.getKey());
                if (!field.getValue().isTextual() || !field.getValue().textValue().matches("[A-Za-z0-9_-]{1,64}")) throw protocol();
                assets.put(field.getKey(), field.getValue().textValue());
            }
            if (assets.size() > count || (state == State.READY ? assets.isEmpty() : !assets.isEmpty())) throw protocol();
            return new Snapshot(id, state, bucket, prefix, expires, (int) count, bytes, assets);
        } catch (IllegalArgumentException invalid) { throw protocol(); }
    }

    private static String route(String id) {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("invalid reception identity");
        }
        return "/" + id;
    }
    private static String text(JsonNode node, String field) {
        if (!node.path(field).isTextual()) throw protocol();
        return node.path(field).textValue();
    }
    private static long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw protocol();
        return value.longValue();
    }
    private static FileReceptionException protocol() { return new FileReceptionException(PROTOCOL, null); }

    /** Bounds allocation before forwarding buffers to the JDK body collector. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final long limit;
        private long received;
        private Flow.Subscription subscription;
        private boolean failed;
        private BoundedBody(long limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > limit) {
                failed = true;
                subscription.cancel();
                delegate.onError(new java.io.IOException("reception response exceeds limit"));
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
