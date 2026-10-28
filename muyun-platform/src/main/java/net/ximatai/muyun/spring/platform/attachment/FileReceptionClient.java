package net.ximatai.muyun.spring.platform.attachment;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Trusted backend access to MuYunFileServer reception tasks, scoped to the current identity.
 * Business services authorize the operation and persist the idempotency key before calling.
 * No operation retries, rotates keys, schedules work or binds a business record automatically.
 * Reception assets are permanent files, not temporary uploads for the form binding lifecycle.
 */
public interface FileReceptionClient {
    Snapshot create(Create request);
    Snapshot get(String receptionId);
    List<StagedObject> objects(String receptionId);
    /** Streams a caller-owned local file; the client does not delete or modify it. */
    void put(String receptionId, String key, Path content);
    Snapshot confirm(String receptionId, List<ObjectEntry> objects);
    Snapshot cancel(String receptionId);

    enum State { OPEN, CONFIRMING, READY, CANCELLED, EXPIRED }

    record Create(String idempotencyKey, int maxObjects, long maxBytes, long expiresInSeconds) {
        public Create {
            if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{1,128}")
                    || maxObjects < 1 || maxBytes < 1 || expiresInSeconds < 1 || expiresInSeconds > 604800) {
                throw new IllegalArgumentException("invalid reception request");
            }
        }
    }
    record Snapshot(String id, State state, String bucket, String prefix, long expiresAt,
                    int maxObjects, long maxBytes, Map<String, String> assets) {
        public Snapshot { assets = Map.copyOf(assets); }
        /** Only an authoritative terminal snapshot with no assets proves retirement. */
        public boolean retiredWithoutAssets() {
            return (state == State.CANCELLED || state == State.EXPIRED) && assets.isEmpty();
        }
    }
    record StagedObject(String key, long sizeBytes) {
        public StagedObject {
            requireObjectKey(key);
            if (sizeBytes < 0) throw new IllegalArgumentException("invalid reception object size");
        }
    }
    record ObjectEntry(String key, String filename, long sizeBytes, String sha256) {
        public ObjectEntry {
            requireObjectKey(key);
            if (filename == null || !filename.matches("[\\p{L}\\p{N} _.-]{1,160}") || sizeBytes < 0
                    || (sha256 != null && !sha256.matches("[a-f0-9]{64}"))) {
                throw new IllegalArgumentException("invalid reception manifest entry");
            }
        }
    }
    /** Relative object paths only; no URL decoding or path normalization is performed. */
    static void requireObjectKey(String key) {
        if (key == null || key.length() > 512 || !key.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
                || java.util.Arrays.stream(key.split("/")).anyMatch(v -> v.equals(".") || v.equals(".."))) {
            throw new IllegalArgumentException("invalid reception object key");
        }
    }
}
