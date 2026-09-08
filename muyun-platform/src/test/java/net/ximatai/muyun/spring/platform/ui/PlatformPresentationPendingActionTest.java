package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformPresentationPendingActionTest {
    @Test
    void rejectsReferencedPendingActionBeforePublishingButAllowsUnreferencedDeclarations() {
        var revisions = mock(PlatformPresentationRevisionService.class);
        var variants = mock(PlatformPresentationVariantService.class);
        var pages = mock(PlatformPageDefinitionService.class);
        var actions = mock(PlatformModuleActionService.class);
        var service = new PlatformPresentationRevisionPublishService(revisions, variants, pages,
                new PlatformPresentationTemplateCatalog());
        ReflectionTestUtils.setField(service, "moduleActionService", actions);
        var page = new PlatformPageDefinition();
        page.setId("page"); page.setModuleAlias("sales.entry"); page.setEnabled(true);
        page.setContractType(PlatformPageContractType.MANAGEMENT);
        var variant = new PlatformPresentationVariant();
        variant.setId("variant"); variant.setPageId("page"); variant.setEnabled(true);
        variant.setClientType(PlatformPresentationClientType.WEB);
        var revision = new PlatformPresentationRevision();
        revision.setId("revision"); revision.setVariantId("variant"); revision.setEnabled(true);
        revision.setStatus(PlatformPresentationRevisionStatus.DRAFT);
        revision.setTemplateAlias("management"); revision.setTemplateVersion(4);
        String tree = """
                {"template":"management","templateVersion":4,"mode":"LIST_CARD","quickSearchFields":[],
                 "actions":[{"actionCode":"approve","anchor":"detail"}],
                 "nodes":[{"slot":"list","title":"列表","fields":[]},{"slot":"form","title":"详情","fields":[]}]}
                """;
        revision.setUiTreeJson(tree);
        var pending = new PlatformModuleAction();
        pending.setActionCode("approve"); pending.setTitle("审批"); pending.setCategory(EntityActionCategory.CUSTOM);
        when(revisions.select("revision")).thenReturn(revision);
        when(variants.requireVisibleVariant("variant")).thenReturn(variant);
        when(pages.requireVisiblePage("page")).thenReturn(page);
        when(actions.listByModuleAliases(List.of("sales.entry"))).thenReturn(List.of(pending));
        assertThatThrownBy(() -> service.publish("revision")).hasMessageContaining("尚未绑定执行能力");
        verify(revisions, never()).update(any());
        pending.setExecutorKey("executor");
        pending.setFormSupported(false);
        String formTree = tree.replace("\"anchor\":\"detail\"", "\"anchor\":\"form\"");
        revision.setUiTreeJson(formTree);
        assertThatThrownBy(() -> service.publish("revision"))
                .hasMessageContaining("审批").hasMessageContaining("不支持表单上下文");
        verify(revisions, never()).update(any());
        revision.setUiTreeJson(tree.replace("{\"actionCode\":\"approve\",\"anchor\":\"detail\"}", ""));
        service.publish("revision");
        verify(revisions).update(argThat(value -> value.getStatus() == PlatformPresentationRevisionStatus.PUBLISHED));
    }
}
