package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Set;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreview;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyCommand;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicModuleOverviewModeServiceTest {
    @Test
    void shouldSaveTreeIntentWithoutMainMetadataOrSchemaMutation() {
        PlatformModuleService modules = new PlatformModuleService(new TestMemoryDao<>());
        PlatformModule module = new PlatformModule();
        module.setAlias("education.tree");
        module.setApplicationAlias("education");
        module.setTitle("树模块");
        module.setModuleKind(ModuleKind.DYNAMIC);
        modules.insert(module);
        ModuleMetadataRelationService relations = mock(ModuleMetadataRelationService.class);
        MetadataService metadata = mock(MetadataService.class);
        MetadataFieldService fields = mock(MetadataFieldService.class);
        var preview = mock(MetadataRelationChangeSetPreviewService.class);
        var apply = mock(MetadataRelationChangeSetApplyService.class);
        var service = new DynamicModuleOverviewModeService(modules, relations, metadata, fields, preview, apply);

        var result = service.save(module.getAlias(), new DynamicModuleOverviewModeSaveCommand(
                DynamicModuleOverviewMode.TREE_CARD, null, Map.of(EntityCapability.TREE, false), null));

        assertThat(result.overviewMode()).isEqualTo(DynamicModuleOverviewMode.TREE_CARD);
        assertThat(modules.select(module.getAlias()).getMainCapabilityDeclarations())
                .containsExactlyInAnyOrder("TREE", "SORT");
        verifyNoInteractions(metadata, fields, preview, apply);
    }

    @Test
    void shouldApplyRequiredTreeCapabilitiesThroughValidatedMainMetadataProposal() {
        PlatformModuleService modules = new PlatformModuleService(new TestMemoryDao<>());
        PlatformModule module = new PlatformModule();
        module.setAlias("education.tree");
        module.setApplicationAlias("education");
        module.setTitle("树模块");
        module.setModuleKind(ModuleKind.DYNAMIC);
        modules.insert(module);
        ModuleMetadataRelationService relations = mock(ModuleMetadataRelationService.class);
        MetadataService metadata = mock(MetadataService.class);
        MetadataFieldService fields = mock(MetadataFieldService.class);
        var preview = mock(MetadataRelationChangeSetPreviewService.class);
        var apply = mock(MetadataRelationChangeSetApplyService.class);
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setId("main-relation");
        relation.setMetadataId("main-metadata");
        Metadata main = new Metadata();
        main.setId("main-metadata");
        main.setVersion(4);
        main.setCapabilityDeclarations(Set.of());
        when(relations.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(relation));
        when(metadata.select("main-metadata")).thenReturn(main);
        var proposal = new MetadataRelationChangeSetPreviewCommand(4,
                Map.of(EntityCapability.TREE, true, EntityCapability.SORT, true), List.of());
        when(preview.preview(module.getAlias(), relation.getId(), proposal)).thenReturn(
                new MetadataRelationChangeSetPreview(module.getAlias(), relation.getId(), main.getId(), 4,
                        Set.of(EntityCapability.TREE, EntityCapability.SORT), List.of(), List.of(), List.of(), List.of(),
                        "validated-fingerprint"));
        var service = new DynamicModuleOverviewModeService(modules, relations, metadata, fields, preview, apply);

        service.save(module.getAlias(), new DynamicModuleOverviewModeSaveCommand(
                DynamicModuleOverviewMode.TREE_CARD, 4, Map.of(EntityCapability.TREE, false), null));

        verify(apply).apply(module.getAlias(), relation.getId(),
                new MetadataRelationChangeSetApplyCommand(proposal, "validated-fingerprint"));
        verify(apply, never()).disableTree(any(), any());
        assertThat(modules.select(module.getAlias()).getMainCapabilityDeclarations())
                .containsExactlyInAnyOrder("TREE", "SORT");
        assertThat(modules.select(module.getAlias()).getOverviewMode()).isEqualTo(DynamicModuleOverviewMode.TREE_CARD);
    }

    @Test
    void shouldPersistTreePresentationRequirementsEvenWhenDirectCallerOmitsThem() {
        DynamicModuleOverviewModeSaveCommand normalized = DynamicModuleOverviewModeService.withRequiredCapabilities(
                new DynamicModuleOverviewModeSaveCommand(DynamicModuleOverviewMode.TREE_CARD, 4,
                        Map.of(EntityCapability.TREE, Boolean.FALSE), null));

        assertThat(normalized.capabilitySelections())
                .containsEntry(EntityCapability.TREE, Boolean.TRUE)
                .containsEntry(EntityCapability.SORT, Boolean.TRUE);
    }

    @Test
    void shouldPersistMicroListOrderingRequirementForDirectCaller() {
        DynamicModuleOverviewModeSaveCommand normalized = DynamicModuleOverviewModeService.withRequiredCapabilities(
                new DynamicModuleOverviewModeSaveCommand(DynamicModuleOverviewMode.MICRO_LIST_CARD, 4, Map.of(), null));

        assertThat(normalized.capabilitySelections())
                .containsEntry(EntityCapability.SORT, Boolean.TRUE);
    }
}
