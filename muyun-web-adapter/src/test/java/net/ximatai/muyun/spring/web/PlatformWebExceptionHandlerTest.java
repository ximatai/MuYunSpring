package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.BusinessLogWriteResult;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogEvent;
import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.ErrorTarget;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collection;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

class PlatformWebExceptionHandlerTest {
    @AfterEach
    void tearDown() {
        RequestTraceContext.clear();
    }

    @Test
    void shouldReturnUnifiedEnvelopeForPlatformExceptionWithTargets() throws Exception {
        MockMvc mvc = mvc(new DemoController());

        mvc.perform(get("/demo/validation")
                        .header(RequestTraceContext.TRACE_ID_HEADER, "trace-1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(RequestTraceContext.TRACE_ID_HEADER, "trace-1"))
                .andExpect(jsonPath("$.traceId").value("trace-1"))
                .andExpect(jsonPath("$.code").value("DYNAMIC_FIELD_REQUIRED"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("客户名称不能为空"))
                .andExpect(jsonPath("$.actionMessage").doesNotExist())
                .andExpect(jsonPath("$.targets[0].kind").value("field"))
                .andExpect(jsonPath("$.targets[0].fieldName").value("customerName"))
                .andExpect(jsonPath("$.targets[0].relationAlias").value("main"));
    }

    @Test
    void shouldReturnActionMessageForBusinessExceptionWithoutLosingHttpStatus() throws Exception {
        MockMvc mvc = mvc(new DemoController());

        mvc.perform(get("/demo/business")
                        .header(RequestTraceContext.TRACE_ID_HEADER, "trace-business")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.traceId").value("trace-business"))
                .andExpect(jsonPath("$.code").value("iam.employee-account.username-occupied"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("登录账号已被占用"))
                .andExpect(jsonPath("$.messageArgs.username").value("demo-admin"))
                .andExpect(jsonPath("$.actionMessage.code").value("iam.employee-account.username-occupied"))
                .andExpect(jsonPath("$.actionMessage.text").value("登录账号已被占用"))
                .andExpect(jsonPath("$.actionMessage.messageArgs.username").value("demo-admin"))
                .andExpect(jsonPath("$.actionMessage.type").value("WARNING"));
    }

    @Test
    void shouldReturnUnifiedEnvelopeForAuthenticationRequired() throws Exception {
        MockMvc mvc = mvc(new DemoController());

        mvc.perform(get("/demo/auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.AUTH_REQUIRED))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("current user context is not available"))
                .andExpect(jsonPath("$.actionMessage.code").value(PlatformErrorCodes.AUTH_REQUIRED))
                .andExpect(jsonPath("$.actionMessage.text").value("current user context is not available"))
                .andExpect(jsonPath("$.actionMessage.type").value("ERROR"));
    }

    @Test
    void shouldReturnAccessDeniedContractWithScope() throws Exception {
        MockMvc mvc = mvc(new DemoController());

        mvc.perform(get("/demo/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.ACCESS_DENIED))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.scope.moduleAlias").value("iam.user"))
                .andExpect(jsonPath("$.scope.actionCode").value("update"))
                .andExpect(jsonPath("$.actionMessage.code").value(PlatformErrorCodes.ACCESS_DENIED))
                .andExpect(jsonPath("$.actionMessage.type").value("ERROR"));
    }

    @Test
    void shouldReturnUnifiedEnvelopeForConfigurationErrorWithScope() throws Exception {
        MockMvc mvc = mvc(new DemoController());

        mvc.perform(get("/demo/config")
                        .header("X-Trace-Id", "trace-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.traceId").value("trace-2"))
                .andExpect(jsonPath("$.code").value("DYNAMIC_DESCRIPTOR_MISSING"))
                .andExpect(jsonPath("$.scope.moduleAlias").value("crm.customer"));
    }

    @Test
    void shouldReturnWarningActionMessageForOptimisticLockConflict() throws Exception {
        MockMvc mvc = mvc(new DemoController());

        mvc.perform(get("/demo/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.CONFLICT_VERSION))
                .andExpect(jsonPath("$.message").value("数据已被更新，请刷新后重试"))
                .andExpect(jsonPath("$.actionMessage.code").value(PlatformErrorCodes.CONFLICT_VERSION))
                .andExpect(jsonPath("$.actionMessage.text").value("数据已被更新，请刷新后重试"))
                .andExpect(jsonPath("$.actionMessage.type").value("WARNING"));
    }

    @Test
    void shouldReturnUniqueConflictContractWithScopeAndDetails() throws Exception {
        MockMvc mvc = mvc(new DemoController());

        mvc.perform(get("/demo/unique-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.CONFLICT_UNIQUE))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.scope.moduleAlias").value("school.hobby"))
                .andExpect(jsonPath("$.details.fields[0]").value("code"))
                .andExpect(jsonPath("$.actionMessage").doesNotExist());
    }

    @Test
    void shouldReturnWarningActionMessageForBadRequest() throws Exception {
        MockMvc mvc = mvc(new DemoController());

        mvc.perform(get("/demo/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("name must not be blank"))
                .andExpect(jsonPath("$.actionMessage.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.actionMessage.text").value("name must not be blank"))
                .andExpect(jsonPath("$.actionMessage.type").value("WARNING"));
    }

    @Test
    void shouldHideMalformedRequestBodyDetailsBehindStableEnvelope() throws Exception {
        MockMvc mvc = mvc(new DemoController());

        mvc.perform(post("/demo/body").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("请求体格式错误"))
                .andExpect(jsonPath("$.actionMessage.type").value("WARNING"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void shouldLocateMalformedRequestParameterWithoutExposingFrameworkMessage() throws Exception {
        MockMvc mvc = mvc(new DemoController());

        mvc.perform(get("/demo/number").param("value", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("请求参数格式错误"))
                .andExpect(jsonPath("$.targets[0].kind").value("field"))
                .andExpect(jsonPath("$.targets[0].fieldName").value("value"));
    }

    @Test
    void shouldLocateMissingRequiredRequestParameter() throws Exception {
        MockMvc mvc = mvc(new DemoController());

        mvc.perform(get("/demo/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("缺少必要请求参数"))
                .andExpect(jsonPath("$.targets[0].fieldName").value("value"));
    }

    @Test
    void shouldReturnStableEnvelopeForUnsupportedMediaType() throws Exception {
        MockMvc mvc = mvc(new DemoController());

        mvc.perform(post("/demo/body").contentType(MediaType.TEXT_PLAIN).content("plain"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.message").value("不支持的请求媒体类型"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void shouldHideUnexpectedExceptionMessage() throws Exception {
        MockMvc mvc = mvc(new DemoController());

        mvc.perform(get("/demo/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.INTERNAL_ERROR))
                .andExpect(jsonPath("$.message").value("系统暂时不可用，请稍后重试"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void shouldRecordTheFinalAdviceErrorOnceWithSafeResponseModelAndKeepResponseWhenPublisherFails() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher();
        MockMvc mvc = mvc(new DemoController(), new RequestErrorLogRecorder(publisher));

        mvc.perform(get("/demo/unexpected").header(RequestTraceContext.TRACE_ID_HEADER, "trace-error"))
                .andExpect(status().isInternalServerError());

        assertThat(publisher.events).singleElement().isInstanceOfSatisfying(RequestErrorLogEvent.class, logged -> {
            assertThat(logged.context().traceId()).isEqualTo("trace-error");
            assertThat(logged.details().method()).isEqualTo("GET");
            assertThat(logged.details().path()).isEqualTo("/demo/unexpected");
            assertThat(logged.details().httpStatus()).isEqualTo(500);
            assertThat(logged.details().errorCode()).isEqualTo(PlatformErrorCodes.INTERNAL_ERROR);
            assertThat(logged.details().responseSummary().value()).contains("code=INTERNAL_ERROR", "message=系统暂时不可用");
            assertThat(logged.details().exceptionType()).isEqualTo(IllegalStateException.class.getName());
            assertThat(logged.details().stackTrace().value()).contains("IllegalStateException");
            assertThat(logged.details().failureStage()).isEqualTo("EXCEPTION_HANDLER");
            assertThat(logged.details().responseCompleted()).isFalse();
        });

        MockMvc failingMvc = mvc(new DemoController(), new RequestErrorLogRecorder(new FailingPublisher()));
        failingMvc.perform(get("/demo/unexpected")).andExpect(status().isInternalServerError());
    }

    @Test
    void shouldTranslateWrappedDatabaseNotNullViolationToUsableValidationError() throws Exception {
        MockMvc mvc = mvc(new DemoController());

        mvc.perform(get("/demo/database-not-null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("字段为必填，请补充后重试"))
                .andExpect(jsonPath("$.actionMessage.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.actionMessage.type").value("WARNING"));
    }

    private MockMvc mvc(Object controller) {
        return mvc(controller, RequestErrorLogRecorder.noop());
    }

    private MockMvc mvc(Object controller, RequestErrorLogRecorder recorder) {
        return MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new RequestTraceWebFilter(recorder))
                .setControllerAdvice(new PlatformWebExceptionHandler(recorder))
                .build();
    }

    private static final class RecordingPublisher implements BusinessLogPublisher {
        private final List<BusinessLogEvent> events = new ArrayList<>();
        @Override public BusinessLogWriteResult publish(BusinessLogEvent event) {
            events.add(event);
            return new BusinessLogWriteResult(event.eventId(), BusinessLogWriteResult.Status.APPENDED);
        }
        @Override public List<BusinessLogWriteResult> publishAll(Collection<? extends BusinessLogEvent> events) {
            return events.stream().map(this::publish).toList();
        }
    }

    private static final class FailingPublisher implements BusinessLogPublisher {
        @Override public BusinessLogWriteResult publish(BusinessLogEvent event) { throw new IllegalStateException("unavailable"); }
        @Override public List<BusinessLogWriteResult> publishAll(Collection<? extends BusinessLogEvent> events) { throw new IllegalStateException("unavailable"); }
    }

    @RestController
    private static class DemoController {
        @GetMapping("/demo/validation")
        String validation() {
            throw PlatformErrors.validation("DYNAMIC_FIELD_REQUIRED", "客户名称不能为空",
                    ErrorTarget.field("customerName").relation("main"));
        }

        @GetMapping("/demo/business")
        String business() {
            throw BusinessExceptions.warning("iam.employee-account.username-occupied", "登录账号已被占用",
                    Map.of("username", "demo-admin"));
        }

        @GetMapping("/demo/auth")
        String auth() {
            throw new AuthenticationRequiredException("current user context is not available");
        }

        @GetMapping("/demo/access-denied")
        String accessDenied() {
            throw new PlatformAccessDeniedException("record data permission denied",
                    ErrorScope.module("iam.user").action("update"));
        }

        @GetMapping("/demo/config")
        String config() {
            throw PlatformErrors.config("DYNAMIC_DESCRIPTOR_MISSING", "模块页面配置不存在",
                    ErrorScope.module("crm.customer"));
        }

        @GetMapping("/demo/optimistic-lock")
        String optimisticLock() {
            throw new OptimisticLockException("record version conflict");
        }

        @GetMapping("/demo/unique-conflict")
        String uniqueConflict() {
            throw PlatformErrors.conflict(PlatformErrorCodes.CONFLICT_UNIQUE,
                    "hobby code already exists", ErrorScope.module("school.hobby"), Map.of("fields", List.of("code")));
        }

        @GetMapping("/demo/bad-request")
        String badRequest() {
            throw new IllegalArgumentException("name must not be blank");
        }

        @PostMapping(value = "/demo/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@RequestBody DemoRequest request) {
            return request.name();
        }

        @GetMapping("/demo/number")
        Integer number(@RequestParam Integer value) {
            return value;
        }

        @GetMapping("/demo/required")
        String required(@RequestParam String value) {
            return value;
        }

        @GetMapping("/demo/unexpected")
        String unexpected() {
            throw new IllegalStateException("database password leaked");
        }

        @GetMapping("/demo/database-not-null")
        String databaseNotNull() {
            throw new IllegalStateException("persistence failed",
                    new SQLException("driver detail must not reach the client", "23502"));
        }

        private record DemoRequest(String name) { }
    }
}
