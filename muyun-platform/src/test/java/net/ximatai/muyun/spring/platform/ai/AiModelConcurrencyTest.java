package net.ximatai.muyun.spring.platform.ai;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelConcurrencyTest {
    enum Mode { TEXT, TEXT_STREAM, TURN, TURN_STREAM }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void limitsUsersAndTotalWhileCallsAreActiveAndReleasesAfterCompletion(Mode mode) throws Exception {
        var entered = new CountDownLatch(2);
        var firstEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AiModelClient client = mock(AiModelClient.class, invocation -> {
            entered.countDown();
            firstEntered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        });
        var gateway = new DefaultAiModelGateway(mock(AiModelRouteResolver.class), client, 2, 1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> call(gateway, mode, "a"));
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertLimit(() -> call(gateway, mode, "a"), "当前用户");
            var second = executor.submit(() -> call(gateway, mode, "b"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertLimit(() -> call(gateway, mode, "c"), "服务正忙");
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            call(gateway, mode, "a");
            call(gateway, mode, "c");
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void releasesPermitsWhenProviderOrConsumerCancels(Mode mode) {
        var failing = new AtomicBoolean(true);
        var client = mock(AiModelClient.class, invocation -> {
            if (failing.get()) throw new CancellationException("cancelled");
            return null;
        });
        var gateway = new DefaultAiModelGateway(mock(AiModelRouteResolver.class), client, 1, 1);
        assertThatThrownBy(() -> call(gateway, mode, "a")).isInstanceOf(CancellationException.class);
        failing.set(false);
        call(gateway, mode, "a");
        call(gateway, mode, "b");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void releasesPermitsWhenRouteResolutionFails(Mode mode) {
        var routes = mock(AiModelRouteResolver.class);
        when(routes.resolveCurrent()).thenThrow(new PlatformException("missing route")).thenReturn(null);
        var gateway = new DefaultAiModelGateway(routes, mock(AiModelClient.class), 1, 1);
        assertThatThrownBy(() -> call(gateway, mode, "a")).hasMessage("missing route");
        call(gateway, mode, "a");
    }

    private static void assertLimit(Runnable invocation, String message) {
        assertThatThrownBy(invocation::run).isInstanceOfSatisfying(PlatformException.class, error -> {
            assertThat(error.httpStatus()).isEqualTo(429);
            assertThat(error.code()).isEqualTo("AI_CONCURRENCY_LIMIT");
            assertThat(error.getMessage()).contains(message);
        });
    }

    private static void call(AiModelGateway gateway, Mode mode, String userId) {
        try (var ignored = CurrentUserContext.use(CurrentUser.systemUser(userId, userId))) {
            var text = AiTextRequest.userText("hello");
            var turn = new AiTurnRequest(List.of(new AiChatMessage(AiChatMessage.Role.USER, "hello")),
                    List.of(), null, null);
            switch (mode) {
                case TEXT -> gateway.generate(text);
                case TEXT_STREAM -> gateway.stream(text, mock(AiTextStreamConsumer.class));
                case TURN -> gateway.complete(turn);
                case TURN_STREAM -> gateway.stream(turn, mock(AiTurnStreamConsumer.class));
            }
        }
    }
}
