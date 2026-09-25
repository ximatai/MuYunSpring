package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.platform.metadata.ModuleChildMetadataCreateCommand;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataOrchestrationService;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.platform.metadata.MetadataChangeSetValidationIssue;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Adds basic input fields through metadata governance and publishes their page in one transaction. */
@Service
public class PageCompositionSaveService {
    private final PlatformPresentationRevisionService revisions;
    private final PlatformPresentationVariantService variants;
    private final PlatformPageDefinitionService pages;
    private final MetadataRelationChangeSetPreviewService preview;
    private final MetadataRelationChangeSetApplyService apply;
    private final PlatformPresentationRevisionPublishService publication;
    private final ModuleMetadataRelationService relations;
    private final MetadataService metadata;
    private final MetadataFieldService fields;
    private final ModuleMetadataOrchestrationService orchestration;

    public PageCompositionSaveService(PlatformPresentationRevisionService revisions,
                                      PlatformPresentationVariantService variants, PlatformPageDefinitionService pages,
                                      MetadataRelationChangeSetPreviewService preview, MetadataRelationChangeSetApplyService apply,
                                      PlatformPresentationRevisionPublishService publication, ModuleMetadataRelationService relations, MetadataService metadata,
                                      ModuleMetadataOrchestrationService orchestration, MetadataFieldService fields) {
        this.revisions = revisions;
        this.variants = variants;
        this.pages = pages;
        this.preview = preview;
        this.apply = apply;
        this.publication = publication;
        this.relations = relations;
        this.metadata = metadata;
        this.orchestration = orchestration;
        this.fields = fields;
    }

    public record ComponentCatalog(String relationId, Integer metadataVersion,
            List<PageCompositionDraftCompiler.ComponentDefinition> components, boolean canCreateChild) {
        public ComponentCatalog withChildCreation(boolean allowed) {
            return new ComponentCatalog(relationId, metadataVersion, components, allowed);
        }
    }

    public ComponentCatalog catalog(String revisionId) {
        var revision = revisions.select(revisionId);
        if (revision == null) throw new IllegalArgumentException("页面不存在");
        var page = pages.requireVisiblePage(variants.requireVisibleVariant(revision.getVariantId()).getPageId());
        var main = relations.list(Criteria.of()
                .eq("moduleAlias", page.getModuleAlias()).eq("relationRole", RelationRole.MAIN),
                PageRequest.of(1, 1)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前模块不支持新增字段"));
        var model = metadata.select(main.getMetadataId());
        // The existing preflight enforces the dynamic-module and relation ownership boundary.
        preview.preview(page.getModuleAlias(), main.getId(),
                new MetadataRelationChangeSetPreviewCommand(model.getVersion(), Map.of(), List.of()));
        return new ComponentCatalog(main.getId(), model.getVersion(), PageCompositionDraftCompiler.components(), false);
    }

    @Transactional
    public PlatformPresentationRevision save(String revisionId, PageCompositionSaveCommand command) {
        if (command == null || command.revision() == null || command.newFields() == null
                || (command.newFields().isEmpty() && command.newChildren().isEmpty()) || command.newFields().size() > 100
                || command.newChildren().size() > 20) {
            throw new IllegalArgumentException("页面新增组件数量必须为 1 至 100 个");
        }
        PlatformPresentationRevision revision = revisions.select(revisionId);
        if (revision == null || revision.getStatus() != PlatformPresentationRevisionStatus.DRAFT
                || !Objects.equals(revision.getVersion(), command.revision().getVersion())) {
            throw BusinessExceptions.warning("platform.page-composition.stale", "页面已变化，请重新加载后保存");
        }
        var variant = variants.requireVisibleVariant(revision.getVariantId());
        var page = pages.requireVisiblePage(variant.getPageId());
        var catalog = catalog(revisionId);
        if (!Objects.equals(catalog.relationId(), command.relationId())
                || !Objects.equals(catalog.metadataVersion(), command.expectedMetadataVersion()))
            throw BusinessExceptions.warning("platform.page-composition.stale", "元数据版本已变化，请重新加载后保存");
        var childDrafts = PageCompositionDraftCompiler.childDrafts(command.revision().getUiTreeJson(), command.newChildren());
        var drafts = PageCompositionDraftCompiler.fieldDrafts(command.revision().getUiTreeJson(), command.newFields());
        var mainNames = PageCompositionFieldNaming.assign(command.newFields(), drafts,
                fields.list(Criteria.of().eq("metadataId", relations.select(command.relationId()).getMetadataId()),
                        new PageRequest(0, Integer.MAX_VALUE)));
        Map<String, Map<String, String>> childNames = new java.util.LinkedHashMap<>();
        var proposal = new MetadataRelationChangeSetPreviewCommand(command.expectedMetadataVersion(), Map.of(), drafts);
        var checked = preview.preview(page.getModuleAlias(), command.relationId(), proposal);
        if (!checked.valid()) throw BusinessExceptions.warning("platform.page-composition.invalid-fields",
                checked.errors().stream().map(MetadataChangeSetValidationIssue::message).reduce((a, b) -> a + "；" + b).orElse("新增字段校验失败"));
        apply.apply(page.getModuleAlias(), command.relationId(),
                new MetadataRelationChangeSetApplyCommand(proposal, checked.proposalFingerprint()));
        var parent = metadata.select(relations.select(command.relationId()).getMetadataId());
        for (var child : command.newChildren()) {
            if (child.fields().isEmpty()) throw new IllegalArgumentException("明细表至少需要一个字段");
            String alias = PageCompositionDraftCompiler.childAlias(child.key());
            var created = orchestration.createChildMetadata(page.getModuleAlias(), command.relationId(),
                    new ModuleChildMetadataCreateCommand(
                            alias, child.title().trim(), parent.getSchemaName(), alias));
            childNames.put(alias, PageCompositionFieldNaming.assign(child.fields(), childDrafts.get(alias),
                    fields.list(Criteria.of().eq("metadataId", created.metadata().getId()), new PageRequest(0, Integer.MAX_VALUE))));
            var childProposal = new MetadataRelationChangeSetPreviewCommand(created.metadata().getVersion(),
                    Map.of(), childDrafts.get(alias));
            var childChecked = preview.preview(page.getModuleAlias(), created.relation().getId(), childProposal);
            if (!childChecked.valid()) throw new IllegalArgumentException(childChecked.errors().stream()
                    .map(MetadataChangeSetValidationIssue::message).collect(java.util.stream.Collectors.joining("；")));
            apply.apply(page.getModuleAlias(), created.relation().getId(),
                    new MetadataRelationChangeSetApplyCommand(childProposal, childChecked.proposalFingerprint()));
        }
        revision.setUiTreeJson(PageCompositionFieldNaming.rewrite(command.revision().getUiTreeJson(), mainNames, childNames));
        revision.setTemplateAlias(command.revision().getTemplateAlias());
        revision.setTemplateVersion(command.revision().getTemplateVersion());
        return publication.saveAndPublish(revisionId, revision);
    }

}
