package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.platform.metadata.*;
import net.ximatai.muyun.spring.platform.runtime.DynamicRuntimeActivationService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationConstructionFieldServiceTest {
    @Test void requiresFormalMetadataPublicationPermissionBeforeReadingConfiguration() {
        var plans = mock(ApplicationConstructionPlanService.class);
        var permissions = mock(ActionExecutionPolicyService.class);
        var service = new ApplicationConstructionFieldService(plans, mock(ApplicationConstructionFieldChangeDao.class),
                mock(MetadataService.class), mock(ModuleMetadataRelationService.class), mock(MetadataFieldService.class),
                mock(FieldSpecService.class), mock(DynamicRuntimeActivationService.class), permissions, mock(ReferenceTargetFieldCatalogService.class), mock(ModuleMetadataFieldPropertySummaryService.class), mock(ModuleMetadataFormulaRuleService.class));
        doThrow(new PlatformAccessDeniedException("无字段发布权限")).when(permissions).requireAuthorized(argThat(context -> context.actionCode().equals("applyMetadataModelChangeSet")));
        try (var ignored = CurrentUserContext.use(CurrentUser.systemUser("restricted", "受限管理员"))) {
            assertThatThrownBy(() -> service.describe("plan", "order")).hasMessageContaining("无字段发布权限");
            assertThatThrownBy(service::designContract).hasMessageContaining("无字段发布权限");
            assertThatThrownBy(service::businessObjects).hasMessageContaining("无字段发布权限");
            assertThatThrownBy(() -> service.referenceTarget("crm.customer")).hasMessageContaining("无字段发布权限");
        }
        verifyNoInteractions(plans);
    }
    @Test void readsCompleteStandardFactsWithoutApplyingAssistantCreationRestrictions() {
        var plans = mock(ApplicationConstructionPlanService.class);
        var metadata = mock(MetadataService.class);
        var relations = mock(ModuleMetadataRelationService.class);
        var fields = mock(MetadataFieldService.class);
        var specs = mock(FieldSpecService.class);
        var service = new ApplicationConstructionFieldService(plans, mock(ApplicationConstructionFieldChangeDao.class),
                metadata, relations, fields, specs, mock(DynamicRuntimeActivationService.class), mock(ActionExecutionPolicyService.class), mock(ReferenceTargetFieldCatalogService.class), mock(ModuleMetadataFieldPropertySummaryService.class), mock(ModuleMetadataFormulaRuleService.class));
        var content = new ApplicationConstructionPlanContent("登记", "登记", java.util.List.of(), java.util.List.of(),
                java.util.List.of(new ApplicationConstructionPlanContent.BusinessObject("entry", "登记", "登记", "sample.entry")),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of());
        when(plans.read("plan")).thenReturn(new ApplicationConstructionPlanService.Snapshot("plan", 1, content,
                java.time.Instant.EPOCH, "LINKED", java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of()));
        var entity = new Metadata(); entity.setId("metadata"); entity.setVersion(1);
        var relation = new ModuleMetadataRelation(); relation.setMetadataId("metadata");
        relation.setModuleAlias("sample.entry"); relation.setRelationRole(RelationRole.MAIN);
        when(metadata.select("metadata")).thenReturn(entity);
        when(relations.list(any(), any(net.ximatai.muyun.database.core.orm.PageRequest.class))).thenAnswer(call ->
                call.getArgument(1, net.ximatai.muyun.database.core.orm.PageRequest.class).getLimit() == 2 ? java.util.List.of(relation) : java.util.List.of());
        var actual = java.util.stream.IntStream.range(0, 257).mapToObj(index -> {
            var field = new MetadataField(); field.setFieldName("field" + index);
            field.setTitle("现有字段".repeat(40)); field.setMetadataId("metadata"); field.setFieldSpecAlias("text");
            return field;
        }).toList();
        when(fields.list(any(), any(net.ximatai.muyun.database.core.orm.PageRequest.class))).thenAnswer(call ->
                actual.stream().limit(call.getArgument(1, net.ximatai.muyun.database.core.orm.PageRequest.class).getLimit()).toList());
        when(specs.list(any(), any(net.ximatai.muyun.database.core.orm.PageRequest.class))).thenReturn(java.util.List.of());
        try (var identity = CurrentUserContext.use(CurrentUser.systemUser("admin", "管理员"))) {
            assertThat(service.designContract()).isEqualTo(MetadataCapabilityCatalog.designContract());
            verifyNoInteractions(plans);
            var result = service.describe("plan", "entry");
            assertThat(result.fields()).hasSize(257);
            assertThat(result.fields().getLast().getFieldName()).isEqualTo("field256");
            assertThat(result.fields().getLast().getTitle()).isEqualTo("现有字段".repeat(40));
            var requirement = new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RULE, 0, "entry",
                    ApplicationConstructionRequirement.Mode.CALCULATION, "field0", "依据实际保存公式，并由用户核验结果", null);
            var withCalculation = new ApplicationConstructionPlanContent(content.title(), content.goal(), content.inScope(), content.outOfScope(),
                    content.objects(), content.relationships(), java.util.List.of("自动计算"), content.questions(), content.assumptions(), content.decisions(),
                    content.acceptanceExamples(), java.util.List.of(requirement));
            var snapshot = plans.read("plan");
            var plan = new ApplicationConstructionPlanService.Snapshot("plan", 1, withCalculation, java.time.Instant.EPOCH,
                    "INITIALIZED", snapshot.initializations(), java.util.List.of(), java.util.List.of(), java.util.List.of());
            var assignment = new ModuleMetadataFormulaRule(); assignment.setExpression("{field0} = 1");
            var computed = new ApplicationConstructionFieldService.Description(result.moduleAlias(), result.planRevision(), result.metadataVersion(),
                    result.fields(), result.specs(), result.references(), result.children(), java.util.List.of(assignment));
            assertThat(service.evidence(plan, "entry", computed)).allSatisfy(item ->
                    assertThat(item.status()).isEqualTo(ApplicationConstructionRequirements.Status.CONFIGURATION_MATCHED));
        }
    }

    @Test void decodesHistoricalFieldReceiptWithoutTreatingItAsANewWriteCommand() {
        var receipt = new ApplicationConstructionFieldChange();
        receipt.setRequestId("historical-request"); receipt.setObjectKey("entry");
        receipt.setPlanRevision(1); receipt.setModuleAlias("legacy.entry");
        receipt.setFieldsJson("""
                [{"name":"note","title":"备注","specAlias":"text","required":false,"unique":false,"indexed":false}]
                """);
        var result = ApplicationConstructionFieldService.receipt(receipt);
        assertThat(result.moduleAlias()).isEqualTo("legacy.entry");
        assertThat(result.fields()).containsExactly(new ApplicationConstructionFieldService.Field(
                "note", "备注", "text", false, false, false, null, false));
    }

}
