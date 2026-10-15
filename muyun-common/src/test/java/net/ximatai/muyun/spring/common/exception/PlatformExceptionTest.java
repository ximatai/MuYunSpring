package net.ximatai.muyun.spring.common.exception;

import net.ximatai.muyun.spring.common.i18n.LanguageContext;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformExceptionTest {
    @Test
    void defaultExceptionKeepsLegacyBadRequestValidationFacts() {
        PlatformException exception = new PlatformException("invalid input");

        assertThat(exception.code()).isEqualTo(PlatformErrorCodes.VALIDATION_FAILED);
        assertThat(exception.httpStatus()).isEqualTo(400);
        assertThat(exception.scope().isEmpty()).isTrue();
        assertThat(exception.targets()).isEmpty();
        assertThat(exception.details()).isEmpty();
        var cause = new IllegalStateException("late cause");
        exception.initCause(cause);
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    void defaultExceptionWithCauseKeepsLegacyBadRequestValidationFacts() {
        IllegalStateException cause = new IllegalStateException("root");
        PlatformException exception = new PlatformException("invalid input", cause);

        assertThat(exception.code()).isEqualTo(PlatformErrorCodes.VALIDATION_FAILED);
        assertThat(exception.httpStatus()).isEqualTo(400);
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    void sourcedRejectionsKeepOriginalFactsAndDoNotTranslateOrInterpolateBusinessArgumentsAgain() {
        var source = TextReference.code("test.rejection.blocked", "不能修改{record}");
        var cause = new IllegalStateException("original cause");
        var args = new java.util.LinkedHashMap<String, Object>();
        args.put("record", "原始 {record}");
        try (var ignored = LanguageContext.use("en")) {
            var exception = PlatformErrors.badRequestText(source, args, cause);
            args.put("record", "changed");

            assertThat(exception.getMessage()).isEqualTo("不能修改原始 {record}");
            assertThat(exception.textSource()).isEqualTo(source);
            assertThat(exception.messageArgs()).containsExactlyEntriesOf(Map.of("record", "原始 {record}"));
            assertThat(exception.code()).isEqualTo(PlatformErrorCodes.VALIDATION_FAILED);
            assertThat(exception.httpStatus()).isEqualTo(400);
            assertThat(exception.getCause()).isSameAs(cause);
            assertThat(exception.scope().isEmpty()).isTrue();
            assertThat(exception.targets()).isEmpty();
            assertThat(exception.details()).isEmpty();
        }
        assertThat(new PlatformException("unowned message").textSource()).isNull();
    }

    @Test
    void validationFactoryUsesUnprocessableEntityAndTargets() {
        PlatformException exception = PlatformErrors.validation("DYNAMIC_FIELD_REQUIRED", "客户名称不能为空",
                ErrorTarget.field("customerName").relation("main"));

        assertThat(exception.code()).isEqualTo("DYNAMIC_FIELD_REQUIRED");
        assertThat(exception.httpStatus()).isEqualTo(422);
        assertThat(exception.targets()).containsExactly(ErrorTarget.field("customerName").relation("main"));
        assertThat(exception.details()).isEmpty();
    }

    @Test
    void exceptionFactsNormalizeBlankCodeAndNullTargets() {
        PlatformException exception = new PlatformException(" ", 422, "invalid field",
                ErrorScope.module("crm.customer"), Arrays.asList(ErrorTarget.field("name"), null),
                Map.of("rule", "required"));

        assertThat(exception.code()).isEqualTo(PlatformErrorCodes.VALIDATION_FAILED);
        assertThat(exception.httpStatus()).isEqualTo(422);
        assertThat(exception.scope()).isEqualTo(ErrorScope.module("crm.customer"));
        assertThat(exception.targets()).containsExactly(ErrorTarget.field("name"));
        assertThat(exception.details()).containsEntry("rule", "required");
    }

    @Test
    void factoriesKeepStableStatusBoundaries() {
        assertThat(PlatformErrors.badRequest("INVALID_QUERY", "bad query").httpStatus()).isEqualTo(400);
        assertThat(PlatformErrors.conflict("DUPLICATE_RECORD_MATCHED", "duplicate", Map.of()).httpStatus())
                .isEqualTo(409);
        assertThat(PlatformErrors.config("DYNAMIC_DESCRIPTOR_MISSING", "missing",
                ErrorScope.module("crm.customer")).httpStatus()).isEqualTo(409);
        assertThat(PlatformErrors.notFound("missing", ErrorScope.module("crm.customer")).httpStatus()).isEqualTo(404);
    }
}
