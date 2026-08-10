package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AsyncActionEndpointWebTest {
    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
        TenantContext.clear();
    }

    @Test
    void shouldReauthorizeCustomSseEndpointAfterAsyncDispatchWithRestoredRequestContext() throws Exception {
        StreamingActionController controller = new StreamingActionController();
        RecordingPolicyService policyService = new RecordingPolicyService();
        MockMvc mvc = standaloneSetup(controller)
                .addFilters(new CurrentUserWebFilter(() -> Optional.of(
                        CurrentUser.tenantUser("user-1", "alice", "tenant-a", "org-1", false))))
                .addInterceptors(new ActionEndpointInterceptor(policyService, new ActionEndpointContextResolver()))
                .build();

        MvcResult initial = mvc.perform(get("/demo.agent/stream").header("Authorization", "Bearer session-1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        controller.send("complete");
        controller.complete();

        mvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk());

        assertThat(policyService.authorizationCount).hasValue(2);
        assertThat(policyService.sawTenantContextOnEveryAuthorization).isTrue();
    }

    private static final class RecordingPolicyService implements ActionExecutionPolicyService {
        private final AtomicInteger authorizationCount = new AtomicInteger();
        private boolean sawTenantContextOnEveryAuthorization = true;

        @Override
        public void requireAuthorized(ActionExecutionContext context) {
            authorize(context);
        }

        @Override
        public ActionAuthorizationResult authorize(ActionExecutionContext context) {
            authorizationCount.incrementAndGet();
            sawTenantContextOnEveryAuthorization &= context.currentUser().isPresent()
                    && TenantContext.currentTenantId().filter("tenant-a"::equals).isPresent();
            return ActionAuthorizationResult.allowed(context, "TEST_ALLOWED");
        }
    }

    @RestController
    @PlatformStaticActionScope(module = "demo.agent")
    private static final class StreamingActionController {
        private SseEmitter emitter;

        @GetMapping(path = "/demo.agent/stream", produces = "text/event-stream")
        @CustomActionEndpoint("stream")
        SseEmitter stream() {
            emitter = new SseEmitter();
            return emitter;
        }

        void send(String value) throws IOException {
            emitter.send(SseEmitter.event().data(value));
        }

        void complete() {
            emitter.complete();
        }
    }
}
