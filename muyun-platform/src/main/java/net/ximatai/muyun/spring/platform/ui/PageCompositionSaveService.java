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
import java.util.Arrays;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;

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
    private final PageCompositionSaveReceiptDao receipts;

    public PageCompositionSaveService(PlatformPresentationRevisionService revisions,
                                      PlatformPresentationVariantService variants, PlatformPageDefinitionService pages,
                                      MetadataRelationChangeSetPreviewService preview, MetadataRelationChangeSetApplyService apply,
                                      PlatformPresentationRevisionPublishService publication, ModuleMetadataRelationService relations, MetadataService metadata,
                                      ModuleMetadataOrchestrationService orchestration, MetadataFieldService fields,
                                      PageCompositionSaveReceiptDao receipts) {
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
        this.receipts = Objects.requireNonNull(receipts);
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
        String requestDigest = requestDigest(revisionId, command);
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
        var published = publication.saveAndPublish(revisionId, revision);
        var receipt = new PageCompositionSaveReceipt();
        receipt.setId(requestDigest.substring(0, 32));
        receipt.setRequestDigest(requestDigest);
        receipt.setRevisionId(revisionId);
        receipt.setTenantId(TenantContext.currentTenantId().orElse(null));
        EntityLifecycle.prepareInsert(receipt, Instant.now());
        receipts.insert(receipt);
        return published;
    }

    /** Proves the original commit, irrespective of field naming or later revision archival. */
    public boolean committed(String revisionId, PageCompositionSaveCommand command) {
        var revision = revisions.select(revisionId);
        if (revision == null) throw new IllegalArgumentException("页面不存在");
        revisions.requireVisibleRevision(revision.getVariantId(), revisionId);
        String digest = requestDigest(revisionId, command);
        var receipt = receipts.findById(digest.substring(0, 32));
        return receipt != null && digest.equals(receipt.getRequestDigest())
                && revisionId.equals(receipt.getRevisionId());
    }

    private static String requestDigest(String revisionId, PageCompositionSaveCommand command) {
        if (command == null || command.revision() == null) throw new IllegalArgumentException("页面保存参数无效");
        var revision = command.revision();
        // Hash the input before field naming; never expose or persist the original UI tree in a receipt.
        var user = CurrentUserContext.currentUser().orElse(null);
        var input = Arrays.asList(revisionId, user == null ? null : user.userId(),
                user == null ? null : user.tenantId(), TenantContext.currentTenantId().orElse(null),
                revision.getVersion(), revision.getTemplateAlias(),
                revision.getTemplateVersion(), revision.getUiTreeJson(), command.relationId(),
                command.expectedMetadataVersion(), command.newFields(), command.newChildren());
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(input);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot identify page composition submission", error);
        }
    }
}
