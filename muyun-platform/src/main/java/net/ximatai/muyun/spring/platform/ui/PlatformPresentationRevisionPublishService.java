package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;

/** Publishes one validated immutable revision atomically inside a presentation variant. */
@Service
public class PlatformPresentationRevisionPublishService {
    private final PlatformPresentationRevisionService revisionService;
    private final PlatformPresentationVariantService variantService;
    private final PlatformPageDefinitionService pageService;
    private final PlatformPresentationTemplateCatalog templateCatalog;
    private final PublishedPageExecutionCoordinator pageExecutionCoordinator;

    @org.springframework.beans.factory.annotation.Autowired
    private net.ximatai.muyun.spring.platform.module.PlatformModuleActionService moduleActionService;

    public PlatformPresentationRevisionPublishService(PlatformPresentationRevisionService revisionService,
                                                      PlatformPresentationVariantService variantService,
                                                      PlatformPageDefinitionService pageService,
                                                      PlatformPresentationTemplateCatalog templateCatalog) {
        this(revisionService, variantService, pageService, templateCatalog, PublishedPageExecutionCoordinator.noop());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PlatformPresentationRevisionPublishService(PlatformPresentationRevisionService revisionService,
                                                      PlatformPresentationVariantService variantService,
                                                      PlatformPageDefinitionService pageService,
                                                      PlatformPresentationTemplateCatalog templateCatalog,
                                                      ObjectProvider<PublishedPageExecutionCoordinator> pageExecutionCoordinator) {
        this(revisionService, variantService, pageService, templateCatalog,
                pageExecutionCoordinator == null ? PublishedPageExecutionCoordinator.noop()
                        : pageExecutionCoordinator.getIfAvailable(PublishedPageExecutionCoordinator::noop));
    }

    PlatformPresentationRevisionPublishService(PlatformPresentationRevisionService revisionService,
                                               PlatformPresentationVariantService variantService,
                                               PlatformPageDefinitionService pageService,
                                               PlatformPresentationTemplateCatalog templateCatalog,
                                               PublishedPageExecutionCoordinator pageExecutionCoordinator) {
        this.revisionService = revisionService;
        this.variantService = variantService;
        this.pageService = pageService;
        this.templateCatalog = templateCatalog;
        this.pageExecutionCoordinator = pageExecutionCoordinator == null
                ? PublishedPageExecutionCoordinator.noop() : pageExecutionCoordinator;
    }

    @Transactional
    public PlatformPresentationRevision publish(String revisionId) {
        PlatformPresentationRevision candidate = requireRevision(revisionId);
        if (!Boolean.TRUE.equals(candidate.getEnabled())) {
            throw BusinessExceptions.warning("platform.presentation-revision.publish-disabled",
                    "Presentation revision must be enabled before publishing: " + revisionId);
        }
        PlatformPresentationVariant variant = variantService.requireVisibleVariant(candidate.getVariantId());
        if (!Boolean.TRUE.equals(variant.getEnabled())) {
            throw BusinessExceptions.warning("platform.presentation-variant.publish-disabled",
                    "Presentation variant must be enabled before publishing: " + candidate.getVariantId());
        }
        PlatformPageDefinition page = pageService.requireVisiblePage(variant.getPageId());
        if (!Boolean.TRUE.equals(page.getEnabled())) {
            throw BusinessExceptions.warning("platform.page-definition.publish-disabled",
                    "Page definition must be enabled before publishing: " + page.getId());
        }
        PlatformPresentationTemplate template = templateCatalog.require(candidate.getTemplateAlias(),
                candidate.getTemplateVersion(), variant.getClientType(), page.getContractType());
        templateCatalog.validateUiTree(candidate, template);

        // The variant is the aggregate root for a revision stream.  Touch it through the normal
        // optimistic-lock path before changing any revision state: two publishers that observed
        // the same stream cannot both make a different revision current.  The losing transaction
        // fails before it archives or publishes a revision and is retried from the fresh draft.
        try (PlatformPresentationRevisionPublishContext.Scope ignored = PlatformPresentationRevisionPublishContext.open()) {
            variantService.update(copyForPublicationLease(variant));
            PlatformPresentationRevision publicationCandidate = requireRevision(revisionId);
            if (!Boolean.TRUE.equals(publicationCandidate.getEnabled())) {
                throw BusinessExceptions.warning("platform.presentation-revision.publish-disabled",
                        "Presentation revision must be enabled before publishing: " + revisionId);
            }
            template = templateCatalog.require(publicationCandidate.getTemplateAlias(), publicationCandidate.getTemplateVersion(),
                    variant.getClientType(), page.getContractType());
            templateCatalog.validateUiTree(publicationCandidate, template);
            validateActionBindings(page.getModuleAlias(), publicationCandidate);
            publishedRevision(variant.getId()).stream()
                    .filter(revision -> !revision.getId().equals(publicationCandidate.getId()))
                    .forEach(revision -> revisionService.update(copyWithStatus(revision,
                            PlatformPresentationRevisionStatus.ARCHIVED)));
            if (publicationCandidate.getStatus() != PlatformPresentationRevisionStatus.PUBLISHED) {
                revisionService.update(copyWithStatus(publicationCandidate, PlatformPresentationRevisionStatus.PUBLISHED));
            }
        }
        // Compile the candidate while this transaction still exposes its published state. The
        // delivery adapter installs its immutable plan only after commit, so a compile failure
        // preserves both the prior revision and the prior executable page.
        pageExecutionCoordinator.prepareAfterPublishedConfigurationChange(page.getModuleAlias());
        return requireRevision(revisionId);
    }

    private void validateActionBindings(String moduleAlias, PlatformPresentationRevision revision) {
        if (moduleActionService == null) return;
        try {
            var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(revision.getUiTreeJson());
            var actions = moduleActionService.listByModuleAliases(java.util.List.of(moduleAlias)).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            net.ximatai.muyun.spring.platform.module.PlatformModuleAction::getActionCode,
                            action -> action));
            for (var entry : tree.path("actions")) {
                String code = entry.path("actionCode").asText();
                var action = actions.get(code);
                if (action == null) continue;
                String title = action.getTitle() == null ? code : action.getTitle();
                if (action.isBindingPending()) throw BusinessExceptions.warning(
                        "platform.presentation-revision.action-binding-pending",
                        "动作“" + title + "”尚未绑定执行能力，请绑定后再发布");
                if ("form".equalsIgnoreCase(entry.path("anchor").asText())
                        && action.getCategory() == net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory.CUSTOM
                        && !Boolean.TRUE.equals(action.getFormSupported())) {
                    throw BusinessExceptions.warning(
                            "platform.presentation-revision.action-form-context-unsupported",
                            "动作“" + title + "”不支持表单上下文，无法发布到表单区域");
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid page action configuration", exception);
        }
    }

    private PlatformPresentationVariant copyForPublicationLease(PlatformPresentationVariant source) {
        PlatformPresentationVariant target = new PlatformPresentationVariant();
        target.setId(source.getId());
        target.setTenantId(source.getTenantId());
        target.setVersion(source.getVersion());
        target.setPageId(source.getPageId());
        target.setClientType(source.getClientType());
        target.setScopeType(source.getScopeType());
        target.setOrganizationId(source.getOrganizationId());
        target.setTitle(source.getTitle());
        target.setEnabled(source.getEnabled());
        target.setSortOrder(source.getSortOrder());
        return target;
    }

    private PlatformPresentationRevision requireRevision(String revisionId) {
        PlatformPresentationRevision revision = revisionId == null || revisionId.isBlank() ? null
                : revisionService.select(revisionId);
        if (revision == null) {
            throw BusinessExceptions.warning("platform.presentation-revision.not-found",
                    "Presentation revision is not found: " + revisionId);
        }
        return revision;
    }

    private java.util.List<PlatformPresentationRevision> publishedRevision(String variantId) {
        return revisionService.list(Criteria.of().eq("variantId", variantId)
                        .eq("status", PlatformPresentationRevisionStatus.PUBLISHED)
                        .eq("enabled", Boolean.TRUE), PageRequest.of(1, 20), Sort.asc("revisionNo"));
    }

    private PlatformPresentationRevision copyWithStatus(PlatformPresentationRevision source,
                                                        PlatformPresentationRevisionStatus status) {
        PlatformPresentationRevision target = new PlatformPresentationRevision();
        target.setId(source.getId());
        target.setTenantId(source.getTenantId());
        target.setVersion(source.getVersion());
        target.setVariantId(source.getVariantId());
        target.setRevisionNo(source.getRevisionNo());
        target.setTemplateAlias(source.getTemplateAlias());
        target.setTemplateVersion(source.getTemplateVersion());
        target.setUiTreeJson(source.getUiTreeJson());
        target.setStatus(status);
        target.setTitle(source.getTitle());
        target.setEnabled(source.getEnabled());
        target.setSortOrder(source.getSortOrder());
        return target;
    }
}
