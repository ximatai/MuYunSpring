package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publishes one validated immutable revision atomically inside a presentation variant. */
@Service
public class PlatformPresentationRevisionPublishService {
    private final PlatformPresentationRevisionService revisionService;
    private final PlatformPresentationVariantService variantService;
    private final PlatformPageDefinitionService pageService;
    private final PlatformPresentationTemplateCatalog templateCatalog;

    public PlatformPresentationRevisionPublishService(PlatformPresentationRevisionService revisionService,
                                                      PlatformPresentationVariantService variantService,
                                                      PlatformPageDefinitionService pageService,
                                                      PlatformPresentationTemplateCatalog templateCatalog) {
        this.revisionService = revisionService;
        this.variantService = variantService;
        this.pageService = pageService;
        this.templateCatalog = templateCatalog;
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

        try (PlatformPresentationRevisionPublishContext.Scope ignored = PlatformPresentationRevisionPublishContext.open()) {
            publishedRevision(variant.getId()).stream()
                    .filter(revision -> !revision.getId().equals(candidate.getId()))
                    .forEach(revision -> revisionService.update(copyWithStatus(revision,
                            PlatformPresentationRevisionStatus.ARCHIVED)));
            if (candidate.getStatus() != PlatformPresentationRevisionStatus.PUBLISHED) {
                revisionService.update(copyWithStatus(candidate, PlatformPresentationRevisionStatus.PUBLISHED));
            }
        }
        return requireRevision(revisionId);
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
