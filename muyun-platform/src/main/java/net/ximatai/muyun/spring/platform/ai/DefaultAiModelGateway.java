package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

@Service
final class DefaultAiModelGateway implements AiModelGateway {
    private final AiModelRouteResolver routeResolver;
    private final AiModelClient client;

    private final int maxConcurrent;
    private final int maxConcurrentPerUser;
    private final Map<String, Integer> activeUsers = new HashMap<>();
    private int activeCalls;

    DefaultAiModelGateway(AiModelRouteResolver routeResolver, AiModelClient client) {
        this(routeResolver, client, 16, 2);
    }

    @Autowired
    DefaultAiModelGateway(AiModelRouteResolver routeResolver, AiModelClient client,
                          @Value("${muyun.ai.max-concurrent:16}") int maxConcurrent,
                          @Value("${muyun.ai.max-concurrent-per-user:2}") int maxConcurrentPerUser) {
        if (maxConcurrent < 1 || maxConcurrentPerUser < 1) {
            throw new IllegalArgumentException("AI concurrency limits must be positive");
        }
        this.maxConcurrent = maxConcurrent;
        this.maxConcurrentPerUser = maxConcurrentPerUser;
        this.routeResolver = Objects.requireNonNull(routeResolver, "routeResolver must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    @Override
    public AiTextResponse generate(AiTextRequest request) {
        return invoke(() -> client.generate(routeResolver.resolveCurrent(), request));
    }

    @Override
    public void stream(AiTextRequest request, AiTextStreamConsumer consumer) {
        invoke(() -> {
            client.stream(routeResolver.resolveCurrent(), request, consumer);
            return null;
        });
    }

    @Override
    public AiTurnResponse complete(AiTurnRequest request) {
        return invoke(() -> client.complete(routeResolver.resolveCurrent(), request));
    }

    @Override
    public void stream(AiTurnRequest request, AiTurnStreamConsumer consumer) {
        invoke(() -> {
            client.stream(routeResolver.resolveCurrent(), request, consumer);
            return null;
        });
    }

    private <T> T invoke(Supplier<T> invocation) {
        // A user's budget follows their identity across page/tenant execution scopes.
        String userId = CurrentUserContext.currentUser().map(CurrentUser::userId).orElse(null);
        acquire(userId);
        try {
            return invocation.get();
        } finally {
            release(userId);
        }
    }

    private synchronized void acquire(String userId) {
        if (activeUsers.getOrDefault(userId, 0) >= maxConcurrentPerUser) {
            throw new PlatformException("AI_CONCURRENCY_LIMIT", 429, "当前用户的 AI 请求过多，请等待已有请求结束后重试");
        }
        if (activeCalls >= maxConcurrent) {
            throw new PlatformException("AI_CONCURRENCY_LIMIT", 429, "AI 服务正忙，请稍后重试");
        }
        activeCalls++;
        activeUsers.merge(userId, 1, Integer::sum);
    }

    private synchronized void release(String userId) {
        activeCalls--;
        activeUsers.compute(userId, (key, count) -> count == 1 ? null : count - 1);
    }
}
