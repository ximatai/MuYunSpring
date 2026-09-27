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
    @Test void requiresFormalMetadataPublicationPermissionBeforeReadingPlanOrPreviewing() {
        var plans = mock(ApplicationConstructionPlanService.class);
        var permissions = mock(ActionExecutionPolicyService.class);
        var previews = mock(MetadataModelChangeSetPreviewService.class);
        var publisher = mock(MetadataModelChangeSetApplyService.class);
        var service = new ApplicationConstructionFieldService(plans, mock(ApplicationConstructionFieldChangeDao.class),
                mock(MetadataService.class), mock(ModuleMetadataRelationService.class), mock(MetadataFieldService.class),
                mock(FieldSpecService.class), previews, publisher, mock(DynamicRuntimeActivationService.class), permissions);
        doThrow(new PlatformAccessDeniedException("无字段发布权限")).when(permissions).requireAuthorized(argThat(context -> context.actionCode().equals("applyMetadataModelChangeSet")));
        try (var ignored = CurrentUserContext.use(CurrentUser.systemUser("restricted", "受限管理员"))) {
            assertThatThrownBy(() -> service.describe("plan", "order")).hasMessageContaining("无字段发布权限");
        }
        verifyNoInteractions(plans, previews, publisher);
    }
    @Test void readsCompleteStandardFactsWithoutApplyingAssistantCreationRestrictions() {
        var plans = mock(ApplicationConstructionPlanService.class);
        var metadata = mock(MetadataService.class);
        var relations = mock(ModuleMetadataRelationService.class);
        var fields = mock(MetadataFieldService.class);
        var specs = mock(FieldSpecService.class);
        var service = new ApplicationConstructionFieldService(plans, mock(ApplicationConstructionFieldChangeDao.class),
                metadata, relations, fields, specs, mock(MetadataModelChangeSetPreviewService.class),
                mock(MetadataModelChangeSetApplyService.class), mock(DynamicRuntimeActivationService.class), mock(ActionExecutionPolicyService.class));
        var content = new ApplicationConstructionPlanContent("登记", "登记", java.util.List.of(), java.util.List.of(),
                java.util.List.of(new ApplicationConstructionPlanContent.BusinessObject("entry", "登记", "登记")),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of());
        when(plans.read("plan")).thenReturn(new ApplicationConstructionPlanService.Snapshot("plan", 1, content,
                java.time.Instant.EPOCH, "INITIALIZED", java.util.List.of(
                new ApplicationConstructionPlanService.Initialization("entry", 1, "sample.entry", "metadata", "relation", "request")), java.util.List.of(), java.util.List.of()));
        var entity = new Metadata(); entity.setId("metadata"); entity.setVersion(1);
        var relation = new ModuleMetadataRelation(); relation.setMetadataId("metadata");
        relation.setModuleAlias("sample.entry"); relation.setRelationRole(RelationRole.MAIN);
        when(metadata.select("metadata")).thenReturn(entity);
        when(relations.select("relation")).thenReturn(relation);
        var actual = java.util.stream.IntStream.range(0, 257).mapToObj(index -> {
            var field = new MetadataField(); field.setFieldName("field" + index);
            field.setTitle("现有字段".repeat(40)); field.setMetadataId("metadata"); field.setFieldSpecAlias("text");
            return field;
        }).toList();
        when(fields.list(any(), any(net.ximatai.muyun.database.core.orm.PageRequest.class))).thenAnswer(call ->
                actual.stream().limit(call.getArgument(1, net.ximatai.muyun.database.core.orm.PageRequest.class).getLimit()).toList());
        when(specs.list(any(), any(net.ximatai.muyun.database.core.orm.PageRequest.class))).thenReturn(java.util.List.of());
        try (var identity = CurrentUserContext.use(CurrentUser.systemUser("admin", "管理员"))) {
            var result = service.describe("plan", "entry");
            assertThat(result.fields()).hasSize(257);
            assertThat(result.fields().getLast().getFieldName()).isEqualTo("field256");
            assertThat(result.fields().getLast().getTitle()).isEqualTo("现有字段".repeat(40));
        }
    }

}
