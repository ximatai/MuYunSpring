package net.ximatai.muyun.spring.ability.action;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionsTest {
    @Test
    void warningUsesUnprocessableEntityAndActionMessage() {
        BusinessException exception = BusinessExceptions.warning("demo.rule-denied", "规则不满足");

        assertThat(exception.httpStatus()).isEqualTo(422);
        assertThat(exception.actionMessage()).isEqualTo(
                new ActionMessage("demo.rule-denied", "规则不满足", ActionMessageType.WARNING));
    }

    @Test
    void conflictKeepsBusinessWarningMessageWithConflictStatus() {
        BusinessException exception = BusinessExceptions.conflict("demo.duplicate", "记录已存在");

        assertThat(exception.httpStatus()).isEqualTo(409);
        assertThat(exception.actionMessage().type()).isEqualTo(ActionMessageType.WARNING);
    }

    @Test
    void warningKeepsDisplayArgumentsSeparateFromTechnicalDetails() {
        BusinessException exception = BusinessExceptions.warning("demo.rule-denied", "记录 {recordTitle} 不满足规则",
                Map.of("recordTitle", "示例记录"));

        assertThat(exception.messageArgs()).containsExactlyEntriesOf(Map.of("recordTitle", "示例记录"));
        assertThat(exception.details()).isEmpty();
        assertThat(exception.actionMessage().messageArgs()).containsExactlyEntriesOf(Map.of("recordTitle", "示例记录"));
    }
    @Test
    void sourcedWarningsPreserveMachineFactsAndFormatBusinessArgumentsOnlyOnce() {
        var source = net.ximatai.muyun.spring.common.i18n.TextReference.code("demo.warning.title", "记录 {title} 不满足规则");
        var scope = net.ximatai.muyun.spring.common.exception.ErrorScope.module("demo.record").action("update");
        var target = net.ximatai.muyun.spring.common.exception.ErrorTarget.record("record");
        var exception = BusinessExceptions.warningText("demo.rule-denied", source, scope, java.util.List.of(target),
                Map.of("technical", "diagnostic"), Map.of("title", "Business {literal}"));
        assertThat(exception.httpStatus()).isEqualTo(422);
        assertThat(exception.getMessage()).isEqualTo("记录 Business {literal} 不满足规则");
        assertThat(exception.scope()).isEqualTo(scope);
        assertThat(exception.targets()).containsExactly(target);
        assertThat(exception.details()).containsEntry("technical", "diagnostic");
        assertThat(exception.actionMessage().textSource()).isEqualTo(source);
        assertThat(exception.messageArgs()).containsEntry("title", "Business {literal}");
        assertThat(BusinessExceptions.conflictText("same.code", source).httpStatus()).isEqualTo(409);
        assertThat(BusinessExceptions.errorText("same.code", source).actionMessage().type()).isEqualTo(ActionMessageType.ERROR);
    }

    @Test
    void resolvesDistinctDisplaySourcesEvenWhenTheirMachineCodeIsShared() {
        var first = net.ximatai.muyun.spring.common.i18n.TextReference.code("same.code.page", "页面已变化");
        var second = net.ximatai.muyun.spring.common.i18n.TextReference.code("same.code.metadata", "元数据已变化");
        var catalog = net.ximatai.muyun.spring.common.i18n.LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        var texts = new net.ximatai.muyun.spring.common.i18n.TextResources(catalog,
                Map.of("en", Map.of(first.sourceId(), "Page changed", second.sourceId(), "Metadata changed")));
        var page = BusinessExceptions.warningText("same.code", first);
        var metadata = BusinessExceptions.warningText("same.code", second);
        assertThat(page.code()).isEqualTo(metadata.code());
        assertThat(texts.resolve(page.actionMessage().textSource(), Map.of(), "en").text()).isEqualTo("Page changed");
        assertThat(texts.resolve(metadata.actionMessage().textSource(), Map.of(), "en").text()).isEqualTo("Metadata changed");
    }

}
