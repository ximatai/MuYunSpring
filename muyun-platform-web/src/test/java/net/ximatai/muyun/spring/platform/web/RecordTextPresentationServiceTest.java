package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.common.option.OptionSourceRegistry;
import net.ximatai.muyun.spring.platform.application.Application;
import net.ximatai.muyun.spring.platform.localization.ConfiguredRecordTextAbility;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.web.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RecordTextPresentationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger reads = new AtomicInteger();
    @SuppressWarnings("unchecked")
    private RecordTextPresentationService service() {
        var original = new Application(); original.setId("row"); original.setTitle("配置名称");
        ConfiguredRecordTextAbility<Application> ability = mock(ConfiguredRecordTextAbility.class, invocation -> switch (invocation.getMethod().getName()) {
            case "getModuleAlias" -> "test.configuration";
            case "modelClass" -> Application.class;
            case "list" -> { reads.incrementAndGet(); yield List.of(original); }
            case "recordTextSources" -> invocation.callRealMethod();
            default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("abilities", new StaticAbilityCatalog(List.of(ability)));
        beans.registerSingleton("languages", LanguageCatalog.load(getClass().getClassLoader(), "zh-CN"));
        beans.registerSingleton("texts", (TextResolver) (source, args, language) -> new TextResources.Resolution(
                "en".equals(language) ? "Configuration name" : source.originalText(), language, language, false, false));
        return new RecordTextPresentationService(mapper, beans.getBeanProvider(StaticAbilityCatalog.class),
                beans.getBeanProvider(TextResolver.class), beans.getBeanProvider(LanguageCatalog.class), beans.getBeanProvider(OptionSourceRegistry.class));
    }

    @Test void labelsOnlyMatchingVisibleFieldsAndKeepsRawRecordsAndNestedBusinessJson() {
        var input = new WebListResponse<>(List.of(Map.of("id", "row", "title", "配置名称", "version", 7,
                "businessJson", Map.of("id", "row", "title", "配置名称")), Map.of("id", "row", "title", "masked"), Map.of("id", "other", "title", "配置名称")));
        var before = mapper.valueToTree(input);
        var output = mapper.valueToTree(service().project("test.configuration", input));
        assertThat(reads).hasValue(1);
        assertThat(output.at("/records/0/title").asText()).isEqualTo("配置名称");
        assertThat(output.at("/records/0/$texts/title/languages/en/text").asText()).isEqualTo("Configuration name");
        assertThat(output.at("/records/0/businessJson/$texts").isMissingNode()).isTrue();
        assertThat(output.at("/records/1/$texts").isMissingNode()).isTrue();
        assertThat(output.at("/records/2/$texts").isMissingNode()).isTrue();
        assertThat(output.at("/records/0/version").asInt()).isEqualTo(7);
        assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(input)).isEqualTo(before);
    }

    @Test void referenceLabelsKeepRawMatchingAndFieldEffectsForListTranslationAndTree() {
        var item = new WebReferenceResolveItem("row", "配置名称", WebReferenceMatchMode.KEY, Map.of("code", "literal"), Map.of("name", "配置名称"), true, "Original subtitle");
        var response = new WebReferenceResolveResponse(WebReferenceResolveStatus.RESOLVED, WebReferenceResolveMode.TRANSLATE,
                List.of(item), List.of(new WebReferenceResolveResult("row", WebReferenceResolveStatus.RESOLVED, WebReferenceMatchMode.KEY, item, List.of(item))),
                0, 10, 1, List.of(new WebTreeNode<>(item, List.of())));
        var output = service().projectReference("test.configuration", "title", response);
        assertThat(reads).hasValue(1);
        assertThat(output.options().getFirst().title()).isEqualTo("配置名称");
        assertThat(output.options().getFirst().titleText().languages().get("en").text()).isEqualTo("Configuration name");
        assertThat(output.results().getFirst().input()).isEqualTo("row");
        assertThat(output.results().getFirst().item().affectPatch()).isEqualTo(item.affectPatch());
        assertThat(output.tree().getFirst().record().hasChildren()).isTrue();
        assertThat(output.tree().getFirst().record().subtitle()).isEqualTo("Original subtitle");
        assertThat(response.options().getFirst().titleText()).isNull();
    }
}
