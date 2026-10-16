package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.model.constraint.FieldInputViolationException;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.TextResolver;
import net.ximatai.muyun.spring.ability.action.ActionMessage;
import net.ximatai.muyun.spring.ability.action.ActionMessageType;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.common.exception.AuthenticationFailedException;
import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformConfigurationException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.ErrorTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;


@RestControllerAdvice
public class PlatformWebExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(PlatformWebExceptionHandler.class);
    private final RequestErrorLogRecorder requestErrorLogRecorder;

    public PlatformWebExceptionHandler() {
        this(RequestErrorLogRecorder.noop());
    }

    @Autowired
    public PlatformWebExceptionHandler(ObjectProvider<RequestErrorLogRecorder> requestErrorLogRecorder) {
        this(requestErrorLogRecorder.getIfAvailable(RequestErrorLogRecorder::noop));
    }

    PlatformWebExceptionHandler(RequestErrorLogRecorder requestErrorLogRecorder) {
        this.requestErrorLogRecorder = requestErrorLogRecorder == null ? RequestErrorLogRecorder.noop() : requestErrorLogRecorder;
    }

    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<PlatformWebError> handleAuthenticationRequired(AuthenticationRequiredException exception) {
        return platformError(exception, ActionMessageType.ERROR);
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<PlatformWebError> handleAuthenticationFailed(AuthenticationFailedException exception) {
        return platformError(exception, ActionMessageType.ERROR);
    }

    @ExceptionHandler(PlatformAccessDeniedException.class)
    public ResponseEntity<PlatformWebError> handleAccessDenied(PlatformAccessDeniedException exception) {
        return platformError(exception, ActionMessageType.ERROR);
    }

    @ExceptionHandler(PlatformConfigurationException.class)
    public ResponseEntity<PlatformWebError> handlePlatformConfiguration(PlatformConfigurationException exception) {
        return platformError(exception);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<PlatformWebError> handleUnreadableRequestBody(HttpMessageNotReadableException exception) {
        return badRequestText("platform.error.request-body-invalid", "请求体格式错误", java.util.List.of(), exception);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<PlatformWebError> handleArgumentTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return badRequestText("platform.error.request-parameter-invalid", "请求参数格式错误", java.util.List.of(ErrorTarget.field(exception.getName())), exception);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<PlatformWebError> handleMissingRequestParameter(MissingServletRequestParameterException exception) {
        return badRequestText("platform.error.request-parameter-missing", "缺少必要请求参数", java.util.List.of(ErrorTarget.field(exception.getParameterName())), exception);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<PlatformWebError> handleMissingRequestPart(MissingServletRequestPartException exception) {
        return badRequestText("platform.error.request-part-missing", "缺少必要请求部分", java.util.List.of(ErrorTarget.field(exception.getRequestPartName())), exception);
    }

    @ExceptionHandler(FieldInputViolationException.class)
    public ResponseEntity<PlatformWebError> handleFieldInputViolation(FieldInputViolationException exception) {
        String key = exception.constraint() == FieldInputViolationException.Constraint.REQUIRED
                ? "platform.validation.required" : "platform.validation.non-null";
        String original = exception.constraint() == FieldInputViolationException.Constraint.REQUIRED
                ? "请填写必填项" : "此字段不能为空";
        var base = PlatformWebError.of(PlatformErrorCodes.VALIDATION_FAILED, 400, original);
        var error = new PlatformWebError(base.traceId(), PlatformErrorCodes.VALIDATION_FAILED, 400, original,
                ActionMessage.warning(PlatformErrorCodes.VALIDATION_FAILED, original), null,
                java.util.List.of(ErrorTarget.field(exception.fieldName())), java.util.Map.of(),
                java.util.Map.of("fieldName", exception.fieldName()));
        return recorded(ResponseEntity.badRequest().body(error.withTextSource(TextReference.code(key, original))), exception);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<PlatformWebError> handleBadRequest(IllegalArgumentException exception) {
        ActionMessage actionMessage = ActionMessage.warning(
                PlatformErrorCodes.VALIDATION_FAILED,
                exception.getMessage());
        return recorded(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(PlatformWebError.of(PlatformErrorCodes.VALIDATION_FAILED, 400,
                        exception.getMessage(), actionMessage)), exception);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<PlatformWebError> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return recorded(ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(PlatformWebError.of(PlatformErrorCodes.VALIDATION_FAILED, 405, "不支持的请求方法").withTextSource(TextReference.code("platform.error.method-not-supported", "不支持的请求方法"))), exception);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<PlatformWebError> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        ActionMessage actionMessage = ActionMessage.warning(PlatformErrorCodes.VALIDATION_FAILED, "不支持的请求媒体类型");
        return recorded(ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(PlatformWebError.of(PlatformErrorCodes.VALIDATION_FAILED, 415,
                        "不支持的请求媒体类型", actionMessage).withTextSource(TextReference.code("platform.error.media-type-not-supported", "不支持的请求媒体类型"))), exception);
    }

    @ExceptionHandler(HttpMessageConversionException.class)
    public ResponseEntity<PlatformWebError> handleMessageConversion(HttpMessageConversionException exception) {
        return badRequestText("platform.error.request-parameter-invalid", "请求参数格式错误", java.util.List.of(), exception);
    }

    /** The response is already unusable after a client disconnect, so no error body can be written. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleDisconnectedClient(AsyncRequestNotUsableException exception) {
        log.debug("Client disconnected before the asynchronous response completed, traceId={}, endpointId={}",
                MDC.get("traceId"), MDC.get("endpointId"));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<PlatformWebError> handleNoHandler(NoHandlerFoundException exception) {
        return recorded(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(PlatformWebError.of(PlatformErrorCodes.RESOURCE_NOT_FOUND, 404, "请求资源不存在").withTextSource(TextReference.code("platform.error.resource-not-found", "请求资源不存在"))), exception);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<PlatformWebError> handleNoResource(NoResourceFoundException exception) {
        return recorded(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(PlatformWebError.of(PlatformErrorCodes.RESOURCE_NOT_FOUND, 404, "请求资源不存在").withTextSource(TextReference.code("platform.error.resource-not-found", "请求资源不存在"))), exception);
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<PlatformWebError> handleOptimisticLock(OptimisticLockException exception) {
        ActionMessage actionMessage = ActionMessage.warning(
                PlatformErrorCodes.CONFLICT_VERSION,
                "数据已被更新，请刷新后重试");
        return recorded(ResponseEntity.status(HttpStatus.CONFLICT)
                .body(PlatformWebError.of(PlatformErrorCodes.CONFLICT_VERSION, 409,
                        "数据已被更新，请刷新后重试", actionMessage).withTextSource(TextReference.code("platform.error.version-conflict", "数据已被更新，请刷新后重试"))), exception);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<PlatformWebError> handleBusinessException(BusinessException exception) {
        return recorded(ResponseEntity.status(exception.httpStatus())
                .body(PlatformWebError.of(exception).withActionMessage(exception.actionMessage())), exception);
    }

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<PlatformWebError> handlePlatformException(PlatformException exception) {
        return platformError(exception);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<PlatformWebError> handleUnexpected(Exception exception) {
        var databaseViolation = DatabaseConstraintViolationResolver.resolve(exception);
        if (databaseViolation.isPresent()) {
            var violation = databaseViolation.get();
            log.warn("Database constraint violation, traceId={}, endpointId={}",
                    MDC.get("traceId"), MDC.get("endpointId"));
            PlatformException translated = new PlatformException(violation.code(), violation.status(),
                    violation.message(), null, violation.targets(), java.util.Map.of());
            var action = ActionMessage.warning(translated.code(), translated.getMessage());
            return recorded(ResponseEntity.status(translated.httpStatus()).body(
                    PlatformWebError.of(translated).withActionMessage(action).withTextSource(violation.textSource())), exception);
        }
        PlatformWebError error = PlatformWebError.of(PlatformErrorCodes.INTERNAL_ERROR, 500,
                "系统暂时不可用，请稍后重试").withTextSource(TextReference.code("platform.error.internal", "系统暂时不可用，请稍后重试"));
        log.error("Unhandled platform web exception, traceId={}, endpointId={}",
                error.traceId(), MDC.get("endpointId"), exception);
        return recorded(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error), exception);
    }

    private ResponseEntity<PlatformWebError> platformError(PlatformException exception) {
        return platformError(exception, exception);
    }

    private ResponseEntity<PlatformWebError> platformError(PlatformException exception,
                                                           ActionMessageType messageType) {
        return platformError(exception, messageType, exception);
    }

    private ResponseEntity<PlatformWebError> platformError(PlatformException exception,
                                                           Throwable cause) {
        return recorded(ResponseEntity.status(exception.httpStatus()).body(PlatformWebError.of(exception)), cause);
    }

    private ResponseEntity<PlatformWebError> platformError(PlatformException exception,
                                                           ActionMessageType messageType,
                                                           Throwable cause) {
        ActionMessage actionMessage = new ActionMessage(exception.code(), exception.getMessage(), messageType,
                exception.messageArgs());
        return recorded(ResponseEntity.status(exception.httpStatus())
                .body(PlatformWebError.of(exception).withActionMessage(actionMessage)), cause);
    }

    private ResponseEntity<PlatformWebError> badRequestText(String key, String message,
                                                            java.util.List<ErrorTarget> targets, Throwable cause) {
        var exception = PlatformErrors.badRequest(PlatformErrorCodes.VALIDATION_FAILED, message, targets);
        return recorded(ResponseEntity.badRequest().body(PlatformWebError.of(exception)
                .withActionMessage(ActionMessage.warning(exception.code(), message))
                .withTextSource(TextReference.code(key, message))), cause);
    }

    private TextResolver textResolver;
    private net.ximatai.muyun.spring.common.i18n.LanguageCatalog languageCatalog;

    @Autowired(required = false)
    void setLanguageCatalog(net.ximatai.muyun.spring.common.i18n.LanguageCatalog catalog) { this.languageCatalog = catalog; }

    @Autowired(required = false)
    void setTextResolver(TextResolver resolver) { this.textResolver = resolver; }

    private PlatformWebError presentationError(PlatformWebError original) {
        return original == null ? null : original.presented(textResolver, languageCatalog);
    }

    private ResponseEntity<PlatformWebError> recorded(ResponseEntity<PlatformWebError> response, Throwable exception) {
        requestErrorLogRecorder.recordException(response.getBody(), exception);
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .body(presentationError(response.getBody()));
    }

}
