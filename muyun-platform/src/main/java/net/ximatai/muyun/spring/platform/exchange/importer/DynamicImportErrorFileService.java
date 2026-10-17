package net.ximatai.muyun.spring.platform.exchange.importer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DynamicImportErrorFileService {
    private static final Duration EXPIRE_AFTER_WRITE = Duration.ofMinutes(30);
    private static final int MAX_SIZE = 256;

    private final Clock clock;
    private final Map<String, ErrorFilePayload> payloads = new ConcurrentHashMap<>();

    @Autowired
    public DynamicImportErrorFileService() {
        this(Clock.systemUTC());
    }

    DynamicImportErrorFileService(Clock clock) {
        this.clock = clock;
    }

    public String save(String moduleAlias, String tenantId, String fileName, byte[] content) {
        if (moduleAlias == null || moduleAlias.isBlank()) {
            throw new IllegalArgumentException("error file moduleAlias must not be blank");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("error file tenantId must not be blank");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("error file content must not be empty");
        }
        cleanup();
        String author = net.ximatai.muyun.spring.common.identity.CurrentUserContext.currentUser()
                .orElseThrow(() -> new IllegalStateException("Error files require an authenticated owner")).userId();
        String token = UUID.randomUUID().toString().replace("-", "");
        payloads.put(token, new ErrorFilePayload(moduleAlias, tenantId, fileName, content, clock.instant(),author));
        trimToMaxSize();
        return token;
    }

    public ErrorFilePayload get(String moduleAlias, String tenantId, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        ErrorFilePayload payload = payloads.get(token);
        if (payload == null) {
            return null;
        }
        if (expired(payload)) {
            payloads.remove(token, payload);
            return null;
        }
        if (!payload.moduleAlias().equals(moduleAlias) || !payload.tenantId().equals(tenantId)) {
            return null;
        }
        if (!net.ximatai.muyun.spring.common.identity.CurrentUserContext.currentUser().map(user -> user.userId().equals(payload.authorId())).orElse(false)) return null;
        return payload;
    }

    private void cleanup() {
        payloads.entrySet().removeIf(entry -> expired(entry.getValue()));
    }

    private void trimToMaxSize() {
        if (payloads.size() <= MAX_SIZE) {
            return;
        }
        payloads.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().createdAt()))
                .limit(payloads.size() - MAX_SIZE)
                .map(Map.Entry::getKey)
                .forEach(payloads::remove);
    }

    private boolean expired(ErrorFilePayload payload) {
        return payload.createdAt().plus(EXPIRE_AFTER_WRITE).isBefore(clock.instant());
    }

    public record ErrorFilePayload(
            String moduleAlias,
            String tenantId,
            String fileName,
            byte[] content,
            Instant createdAt,
            String authorId
    ) {
        public ErrorFilePayload {
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
