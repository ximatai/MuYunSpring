package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.PageRequest;
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
    private ConfiguredRecordTextAbility<Application> sourceAbility;
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
        sourceAbility = ability;
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

    @Test @SuppressWarnings("unchecked")
    void projectsOnlyDeclaredReferenceSummariesAndKeepsTheirRawIdentity() {
        var control = new net.ximatai.muyun.spring.platform.metadata.FieldUiControl(); control.setId("text"); control.setTitle("文本");
        ConfiguredRecordTextAbility<net.ximatai.muyun.spring.platform.metadata.FieldUiControl> target = mock(ConfiguredRecordTextAbility.class, withSettings().extraInterfaces(net.ximatai.muyun.spring.ability.DataScopeAbility.class));
        when(target.getModuleAlias()).thenReturn("platform.field_ui_control");
        doReturn(net.ximatai.muyun.spring.platform.metadata.FieldUiControl.class).when(target).modelClass();
        var scoped = (net.ximatai.muyun.spring.ability.DataScopeAbility<?>) target;
        when(scoped.readScope(eq(net.ximatai.muyun.spring.common.platform.PlatformAction.REFERENCE), any()))
                .thenAnswer(call -> net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult.crossTenantRestricted(call.getArgument(1)));
        doCallRealMethod().when(scoped).withDataScopeTenant(any(), any());
        when(target.list(any(), any(PageRequest.class))).thenAnswer(call -> {
            assertThat(net.ximatai.muyun.spring.common.tenant.TenantContext.tenantFilterBypassed()).isTrue();
            return List.of(control);
        });
        when(target.recordTextSources(control)).thenReturn(Map.of("title", TextReference.configured("config/control/text/title", "文本")));
        net.ximatai.muyun.spring.ability.CrudAbility<?> owner = mock(net.ximatai.muyun.spring.ability.CrudAbility.class);
        doReturn(net.ximatai.muyun.spring.platform.metadata.FieldSpec.class).when(owner).modelClass();
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("abilities", new StaticAbilityCatalog(List.of(target)));
        beans.registerSingleton("languages", LanguageCatalog.load(getClass().getClassLoader(), "zh-CN"));
        beans.registerSingleton("texts", (TextResolver) (source, args, language) -> new TextResources.Resolution(
                "en".equals(language) ? "Text" : source.originalText(), language, language, false, false));
        var texts = new RecordTextPresentationService(mapper, beans.getBeanProvider(StaticAbilityCatalog.class),
                beans.getBeanProvider(TextResolver.class), beans.getBeanProvider(LanguageCatalog.class), beans.getBeanProvider(OptionSourceRegistry.class));
        var summary = Map.of("id", "text", "title", "文本", "alias", "text");
        var input = Map.<String, Object>of("id", "string", "defaultUiControlAlias", "text", "defaultUiControlSummary", summary,
                "uiControlAliases", List.of("text"), "uiControlSummaries", List.of(summary, Map.of("id", "other", "title", "文本")), "businessJson", summary);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
        try {
            texts.captureProjected(owner, List.of(input), RecordReadVisibility.ACTIVE);
            assertThat(net.ximatai.muyun.spring.common.tenant.TenantContext.tenantFilterBypassed()).isFalse();
            var result = mapper.valueToTree(texts.projectCaptured(input));
            assertThat(result.at("/defaultUiControlSummary/title").asText()).isEqualTo("文本");
            assertThat(result.at("/defaultUiControlSummary/$texts/title/languages/en/text").asText()).isEqualTo("Text");
            assertThat(result.at("/uiControlSummaries/0/$texts/title/languages/en/text").asText()).isEqualTo("Text");
            assertThat(result.at("/uiControlSummaries/1/$texts").isMissingNode()).isTrue();
            assertThat(result.at("/businessJson/$texts").isMissingNode()).isTrue();
            assertThat(mapper.valueToTree(input).at("/defaultUiControlSummary/$texts").isMissingNode()).isTrue();
            when(target.list(any(), any(PageRequest.class))).thenReturn(List.of());
            texts.captureProjected(owner, List.of(input), RecordReadVisibility.ACTIVE);
            assertThat(mapper.valueToTree(texts.projectCaptured(input)).at("/defaultUiControlSummary/$texts").isMissingNode()).isTrue();
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
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
    @Test void capturesAlreadyReadSourcesBeforeScopeClosesWithoutAnySupplementaryRead() {
        var service = service();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            var record = new Application(); record.setId("row"); record.setTitle("配置名称");
            try (var tenant = net.ximatai.muyun.spring.common.tenant.TenantContext.use("tenant")) {
                try (var system = net.ximatai.muyun.spring.common.tenant.TenantContext.system("authorized global read")) {
                    service.transformRecord(sourceAbility, record, net.ximatai.muyun.spring.ability.output.RecordOutputContext.view());
                }
                var output = mapper.valueToTree(service.projectCaptured(List.of(Map.of("id", "row", "title", "配置名称"))));
                assertThat(output.at("/0/$texts/title/languages/en/text").asText()).isEqualTo("Configuration name");
                assertThat(net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId()).contains("tenant");
            }
            assertThat(reads).hasValue(0);
            // A new request cannot reuse the previous response's sources.
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            assertThat(service.projectCaptured(Map.of("id", "row", "title", "配置名称")))
                    .isEqualTo(Map.of("id", "row", "title", "配置名称"));
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }

    @Test void retainedSqlProjectionUsesRetainedReadAndKeepsLifecycleEnvelope() {
        var service = service();
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
        try {
            var original = new Application(); original.setId("row"); original.setTitle("配置名称"); original.setDeleted(true);
            RetainedConfiguration ability = mock(RetainedConfiguration.class);
            doReturn(Application.class).when(ability).modelClass();
            when(ability.recordTextSources(original)).thenReturn(Map.of("title", TextReference.code("test.retained", "配置名称")));
            when(ability.pageRecycleBin(any(), any())).thenReturn(net.ximatai.muyun.database.core.orm.PageResult.of(
                    List.of(original), 1, net.ximatai.muyun.database.core.orm.PageRequest.of(1, 1)));
            service.captureProjected(ability, List.of(Map.of("id", "row", "title", "配置名称")), RecordReadVisibility.RETAINED);
            var envelope = Map.of("records", List.of(Map.of("record", Map.of("id", "row", "title", "配置名称"), "sourceDeleteOperationId", "deletion")));
            var output = mapper.valueToTree(service.projectCaptured(envelope));
            assertThat(output.at("/records/0/record/$texts/title/languages/en/text").asText()).isEqualTo("Configuration name");
            assertThat(output.at("/records/0/sourceDeleteOperationId").asText()).isEqualTo("deletion");
            verify(ability, never()).list(any(), any(net.ximatai.muyun.database.core.orm.PageRequest.class));
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }

    @Test void conflictingProducerSourcesAreNotAppliedToCoincidingRecordIds() {
        var service = service();
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
        try {
            var record = new Application(); record.setId("row"); record.setTitle("配置名称");
            service.transformRecord(sourceAbility, record, net.ximatai.muyun.spring.ability.output.RecordOutputContext.view());
            doReturn(Map.of("title", TextReference.code("other.producer", "配置名称"))).when(sourceAbility).recordTextSources(record);
            service.transformRecord(sourceAbility, record, net.ximatai.muyun.spring.ability.output.RecordOutputContext.view());
            var output = mapper.valueToTree(service.projectCaptured(record));
            assertThat(output.path("$texts").isMissingNode()).isTrue();
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }

    @Test
    void dynamicEnvelopesProjectOnlyDeclaredMatchingReferenceFieldsAndKeepChildAndBusinessValues() {
        var service = service();
        var entity = new net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition("entry", "test_entry", "Entry",
                List.of(net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition.string("configuration", "Configuration")));
        var module = net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition.builder("test.entry", "Entries")
                .entities(List.of(entity)).references(List.of(new net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition(
                        "entry", "configuration", "test.configuration",
                        net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE,
                        List.of(new net.ximatai.muyun.spring.ability.reference.ReferenceProjection("title", "configurationTitle")))))
                .build();
        var record = new net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord(entity);
        record.setId("owner"); record.setTenantId("tenant-a"); record.setValue("configuration", "row");
        record.putProjectedValue("configurationTitle", "配置名称");
        var child = record.copy(); child.setId("child"); record.setChildren("lines", List.of(child));
        var business = Map.of("id", "child", "values", Map.of("configurationTitle", "配置名称"));
        var input = Map.of("id", "owner", "version", 7, "values", Map.of("configuration", "row", "configurationTitle", "配置名称", "businessJson", business),
                "children", Map.of("lines", List.of(Map.of("id", "child", "values", Map.of("configurationTitle", "配置名称")))));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
        try (var tenant = net.ximatai.muyun.spring.common.tenant.TenantContext.use("reader")) {
            doAnswer(call -> {
                assertThat(net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId()).contains("tenant-a");
                var original = new Application(); original.setId("row"); original.setTitle("配置名称");
                return List.of(original);
            }).when(sourceAbility).list(any(), any(PageRequest.class));
            service.captureDynamicReferences(module, List.of(record));
            assertThat(net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId()).contains("reader");
            var output = mapper.valueToTree(service.projectCaptured(input));
            assertThat(output.at("/$texts/configurationTitle/languages/en/text").asText()).isEqualTo("Configuration name");
            assertThat(output.at("/children/lines/0/$texts/configurationTitle/languages/en/text").asText()).isEqualTo("Configuration name");
            assertThat(output.at("/values/configurationTitle").asText()).isEqualTo("配置名称");
            assertThat(output.at("/values/configuration").asText()).isEqualTo("row");
            assertThat(output.at("/values/businessJson/$texts").isMissingNode()).isTrue();
            assertThat(output.path("version").asInt()).isEqualTo(7);
            assertThat(mapper.valueToTree(input).path("$texts").isMissingNode()).isTrue();
            var masked = Map.of("id", "owner", "values", Map.of("configurationTitle", "masked"));
            assertThat(mapper.valueToTree(service.projectCaptured(masked)).path("$texts").isMissingNode()).isTrue();
            when(sourceAbility.list(any(), any(PageRequest.class))).thenReturn(List.of());
            service.captureDynamicReferences(module, List.of(record));
            assertThat(mapper.valueToTree(service.projectCaptured(input)).path("$texts").isMissingNode()).isTrue();
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }

    private interface RetainedConfiguration extends ConfiguredRecordTextAbility<Application>, net.ximatai.muyun.spring.ability.RecycleBinAbility<Application> { }

}
