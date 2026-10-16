package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTextReadListener;
import net.ximatai.muyun.spring.common.content.BusinessContentTextPresentation;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.web.BusinessContentQueryWebContext;
import net.ximatai.muyun.spring.web.WebListResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BusinessContentRecordPresentationServiceTest {
    private static final ReferenceTarget TARGET = ReferenceTarget.of("test", "category");
    private final ObjectMapper mapper = new ObjectMapper();
    private final BusinessContentReadingProjectionService readings = mock(BusinessContentReadingProjectionService.class);
    private BusinessContentRecordPresentationService service;
    private CrudAbility<StandardTitledEntity> targetOwner;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        var request = new MockHttpServletRequest(); request.addHeader(BusinessContentQueryWebContext.LANGUAGES_HEADER, "zh-CN,en,de");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        CrudAbility<StandardTitledEntity> owner = mock(CrudAbility.class); targetOwner = owner;
        doReturn(StandardTitledEntity.class).when(owner).modelClass(); when(owner.getModuleAlias()).thenReturn("test.category");
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("targets", new StaticAbilityCatalog(List.of(owner)));
        service = new BusinessContentRecordPresentationService(mapper, readings, beans.getBeanProvider(StaticAbilityCatalog.class),
                beans.getBeanProvider(DynamicRecordService.class));
        when(readings.presentations(eq(owner), anyMap(), anySet(), anyCollection())).thenAnswer(invocation -> {
            Map<String, Map<String, Object>> originals = invocation.getArgument(1);
            Map<String, Map<String, BusinessContentTextPresentation>> result = new LinkedHashMap<>();
            originals.forEach((id, fields) -> {
                Map<String, BusinessContentTextPresentation> values = new LinkedHashMap<>();
                fields.forEach((field, original) -> values.put(field, new BusinessContentTextPresentation((String) original,
                        Map.of("en", "Equipment " + id, "de", "Gerät " + id))));
                result.put(id, values);
            });
            return result;
        });
    }

    @AfterEach void reset() { RequestContextHolder.resetRequestAttributes(); }

    @Test void projectsOnlyExposedMatchingTextAndLeavesDomainValuesAndBusinessJsonUntouched() {
        var owner = new LinkedHashMap<String, Object>(Map.of("id", "owner", "categoryId", "a", "categoryTitle", "设备原名"));
        owner.put("businessJson", Map.of("id", "owner", "categoryId", "a", "categoryTitle", "设备原名"));
        service.onProjection(fact(owner, "a", "设备原名"));
        var before = mapper.valueToTree(owner);
        var output = mapper.valueToTree(service.projectCaptured(new WebListResponse<>(List.of(owner))));
        assertThat(output.at("/records/0/categoryTitle").asText()).isEqualTo("设备原名");
        assertThat(output.at("/records/0/categoryId").asText()).isEqualTo("a");
        assertThat(output.at("/records/0/$contentTexts/categoryTitle/readings/de").asText()).isEqualTo("Gerät a");
        assertThat(output.at("/records/0/businessJson/$contentTexts").isMissingNode()).isTrue();
        assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(owner)).isEqualTo(before);
        verify(readings, times(1)).presentations(any(), anyMap(), anySet(), eq(List.of("zh-CN", "en", "de")));
    }

    @Test void neverInfersResponseContainersFromArbitraryBusinessJsonKeys() {
        var owner = Map.of("id", "owner", "categoryId", "a", "categoryTitle", "original");
        service.onProjection(fact(owner, "a", "original"));
        var businessJson = Map.of("record", owner, "records", List.of(owner), "children", List.of(owner));
        assertThat(service.projectCaptured(businessJson)).isSameAs(businessJson);
        verifyNoInteractions(readings);
        var output = mapper.valueToTree(service.projectCaptured(new WebListResponse<>(List.of(owner, businessJson))));
        assertThat(output.at("/records/0/$contentTexts/categoryTitle/readings/en").asText()).isEqualTo("Equipment a");
        assertThat(output.at("/records/1")).isEqualTo(mapper.valueToTree(businessJson));
    }

    @Test void doesNotEvenReadTranslationsForHiddenMaskedOrReboundOutputs() {
        var owner = Map.of("id", "owner", "categoryId", "a", "categoryTitle", "设备原名");
        service.onProjection(fact(owner, "a", "设备原名"));
        for (var output : List.of(Map.of("id", "owner", "categoryId", "a"),
                Map.of("id", "owner", "categoryId", "a", "categoryTitle", "masked"),
                Map.of("id", "owner", "categoryId", "b", "categoryTitle", "设备原名"))) {
            assertThat(mapper.valueToTree(service.projectCaptured(output)).path("$contentTexts").isMissingNode()).isTrue();
        }
        verifyNoInteractions(readings);
    }

    @Test void keepsSameNamedTargetsDistinctAndRejectsAnIdentityReorderWithUnchangedRawTitles() {
        var owner = Map.of("id", "owner", "categoryIds", List.of("b", "a"), "categoryTitles", List.of("同名", "同名"));
        service.onProjection(new ReferenceTextReadListener.Projection(owner, "categoryIds", List.of("b", "a"),
                "categoryTitles", null, null, true, List.of(text("b", "同名"), text("a", "同名"))));
        var output = mapper.valueToTree(service.projectCaptured(owner));
        assertThat(output.at("/$contentTexts/categoryTitles/0/readings/en").asText()).isEqualTo("Equipment b");
        assertThat(output.at("/$contentTexts/categoryTitles/1/readings/en").asText()).isEqualTo("Equipment a");
        clearInvocations(readings);
        var reordered = new LinkedHashMap<String, Object>(owner); reordered.put("categoryIds", List.of("a", "b"));
        assertThat(mapper.valueToTree(service.projectCaptured(reordered)).path("$contentTexts").isMissingNode()).isTrue();
        verifyNoInteractions(readings);
    }

    @Test void bindsSummaryMetadataToItsDeclaredParentFieldAndTargetId() {
        var summary = Map.of("id", "a", "title", "设备原名");
        var owner = Map.of("id", "owner", "categoryId", "a", "category", summary, "businessJson", summary);
        service.onProjection(new ReferenceTextReadListener.Projection(owner, "categoryId", List.of("a"), "title",
                "category", "a", false, List.of(text("a", "设备原名"))));
        var output = mapper.valueToTree(service.projectCaptured(owner));
        assertThat(output.at("/category/$contentTexts/title/readings/en").asText()).isEqualTo("Equipment a");
        assertThat(output.at("/businessJson/$contentTexts").isMissingNode()).isTrue();
        assertThat(output.at("/category/id").asText()).isEqualTo("a");
    }

    @Test void rejectsAmbiguousOwnerFieldProducersWithoutResurrectingTheFirstSource() {
        var owner = Map.of("id", "owner", "categoryId", "a", "categoryTitle", "同名");
        service.onProjection(fact(owner, "a", "同名"));
        service.onProjection(fact(owner, "b", "同名"));
        service.onProjection(fact(owner, "a", "同名"));
        assertThat(mapper.valueToTree(service.projectCaptured(owner)).path("$contentTexts").isMissingNode()).isTrue();
        verifyNoInteractions(readings);
    }

    @Test void separatesDynamicEnvelopesFromFlatExpansionsWithBusinessFieldsNamedValuesAndChildren() {
        var entity = new EntityDefinition("main", "reference_test", "Reference", List.of(new FieldDefinition(
                "categoryId", "category_id", net.ximatai.muyun.spring.dynamic.metadata.FieldType.TEXT, "Category")));
        var dynamic = new DynamicRecord(entity).setValue("categoryId", "a"); dynamic.setId("owner");
        service.onProjection(fact(dynamic, "a", "设备原名"));
        service.captureDynamic(null, List.of(dynamic), true);
        var owner = Map.of("id", "owner", "categoryId", "a", "categoryTitle", "设备原名",
                "values", Map.of("categoryTitle", "设备原名"), "children", Map.of("fake", List.of(Map.of("id", "owner", "categoryTitle", "设备原名"))));
        var output = mapper.valueToTree(service.projectCaptured(new WebListResponse<>(List.of(owner))));
        assertThat(output.at("/records/0/$contentTexts/categoryTitle/readings/de").asText()).isEqualTo("Gerät a");
        assertThat(output.at("/records/0/values/$contentTexts").isMissingNode()).isTrue();
        assertThat(output.at("/records/0/children/fake/0/$contentTexts").isMissingNode()).isTrue();
    }

    @Test void projectsOwnDeclaredTextInsideTreeRecordsWithoutMutatingRawValues() {
        when(readings.fields(targetOwner)).thenReturn(Set.of("title"));
        var record = new StandardTitledEntity() { }; record.setId("a"); record.setTitle("设备原名");
        service.transformRecord(targetOwner, record, net.ximatai.muyun.spring.ability.output.RecordOutputContext.list());
        var child = new StandardTitledEntity() { }; child.setId("b"); child.setTitle("子设备");
        service.transformRecord(targetOwner, child, net.ximatai.muyun.spring.ability.output.RecordOutputContext.list());
        var body = new WebListResponse<>(List.of(new net.ximatai.muyun.spring.web.WebTreeNode<>(record,
                List.of(new net.ximatai.muyun.spring.web.WebTreeNode<>(child, List.of())))));
        var output = mapper.valueToTree(service.projectCaptured(body));
        assertThat(output.at("/records/0/record/$contentTexts/title/readings/de").asText()).isEqualTo("Gerät a");
        assertThat(output.at("/records/0/children/0/record/$contentTexts/title/readings/en").asText()).isEqualTo("Equipment b");
        assertThat(record.getTitle()).isEqualTo("设备原名");
    }

    @Test void keepsCandidateTitleContextAndBusinessPatchesSeparateAndWithdrawsDeniedContext() {
        var origin = new net.ximatai.muyun.spring.ability.reference.ReferenceLoadReader.ResolvedValue(TARGET, "parent", "title", " 原分类 ");
        service.onCandidateSubtitles(new ReferenceTextReadListener.CandidateSubtitles(TARGET, "parentId.title", List.of("a"), Map.of("a", origin)));
        var item = new net.ximatai.muyun.spring.web.WebReferenceResolveItem("a", "设备原名", null,
                Map.of("title", "设备原名"), Map.of("snapshot", "设备原名")).withSubtitle("原分类");
        var response = new net.ximatai.muyun.spring.web.WebReferenceResolveResponse(
                net.ximatai.muyun.spring.web.WebReferenceResolveStatus.RESOLVED,
                net.ximatai.muyun.spring.web.WebReferenceResolveMode.QUERY, List.of(item), List.of(), 0, 20, 1);
        var projected = service.projectReference(TARGET, "title", "parentId.title", response).options().getFirst();
        assertThat(projected.title()).isEqualTo("设备原名");
        assertThat(projected.titleContent().readings()).containsEntry("en", "Equipment a");
        assertThat(projected.subtitle()).isEqualTo("原分类");
        assertThat(projected.subtitleContent().originalText()).isEqualTo("原分类");
        assertThat(projected.subtitleContent().readings()).containsEntry("de", "Gerät parent");
        assertThat(projected.projections()).isEqualTo(item.projections());
        assertThat(projected.affectPatch()).isEqualTo(item.affectPatch());
        service.onCandidateSubtitles(new ReferenceTextReadListener.CandidateSubtitles(TARGET, "parentId.title", List.of("a"), Map.of()));
        service.onCandidateSubtitles(new ReferenceTextReadListener.CandidateSubtitles(TARGET, "parentId.title", List.of("a"), Map.of("a", origin)));
        assertThat(service.projectReference(TARGET, "title", "parentId.title", response).options().getFirst().subtitleContent()).isNull();
    }

    private ReferenceTextReadListener.Projection fact(Object owner, String id, String original) {
        return new ReferenceTextReadListener.Projection(owner, "categoryId", List.of(id), "categoryTitle", null, null, false, List.of(text(id, original)));
    }
    private static ReferenceTextReadListener.Text text(String id, String original) {
        return new ReferenceTextReadListener.Text(TARGET, id, "title", original);
    }
}
