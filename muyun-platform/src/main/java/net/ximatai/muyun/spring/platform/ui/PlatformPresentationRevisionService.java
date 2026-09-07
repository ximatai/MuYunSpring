package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Set;
import java.util.EnumSet;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.database.core.orm.PageRequest;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.action.BusinessExceptions;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Owns immutable published presentation snapshots while a variant remains a stable address. */
@Service
public class PlatformPresentationRevisionService extends AbstractAbilityService<PlatformPresentationRevision> implements
        SoftDeleteAbility<PlatformPresentationRevision>,
        EnableAbility<PlatformPresentationRevision>,
        SortAbility<PlatformPresentationRevision>,
        QueryAbility<PlatformPresentationRevision> {
    public static final String MODULE_ALIAS = "platform.presentation_revision";

    private static final ObjectMapper JSON = new ObjectMapper();
    private final PlatformPresentationVariantService variantService;
    private final PublishedPageExecutionCoordinator pageExecutionCoordinator;

    public PlatformPresentationRevisionService(BaseDao<PlatformPresentationRevision, String> revisionDao,
                                               PlatformPresentationVariantService variantService) {
        this(revisionDao, variantService, PublishedPageExecutionCoordinator.noop());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PlatformPresentationRevisionService(BaseDao<PlatformPresentationRevision, String> revisionDao,
                                               PlatformPresentationVariantService variantService,
                                               ObjectProvider<PublishedPageExecutionCoordinator> pageExecutionCoordinator) {
        this(revisionDao, variantService, pageExecutionCoordinator == null ? PublishedPageExecutionCoordinator.noop()
                : pageExecutionCoordinator.getIfAvailable(PublishedPageExecutionCoordinator::noop));
    }

    PlatformPresentationRevisionService(BaseDao<PlatformPresentationRevision, String> revisionDao,
                                        PlatformPresentationVariantService variantService,
                                        PublishedPageExecutionCoordinator pageExecutionCoordinator) {
        super(MODULE_ALIAS, PlatformPresentationRevision.class, revisionDao);
        this.variantService = variantService;
        this.pageExecutionCoordinator = pageExecutionCoordinator == null
                ? PublishedPageExecutionCoordinator.noop() : pageExecutionCoordinator;
    }

    /** Legacy pages resolve their mode from the module; require an explicit v2 publication before changing it. */
    public boolean hasLegacyPublishedPage(String moduleAlias) {
        var variants = variantService.variantIdsForModule(moduleAlias);
        return !variants.isEmpty() && !list(Criteria.of().in("variantId", variants)
                        .eq("status", PlatformPresentationRevisionStatus.PUBLISHED)
                        .eq("templateVersion", PlatformPresentationTemplateCatalog.MANAGEMENT_VERSION),
                new PageRequest(0, 1)).isEmpty();
    }

    /** A mode change cannot remove capabilities still used by a published page. */
    public Set<EntityCapability> publishedRequiredCapabilities(String moduleAlias) {
        var variants = variantService.variantIdsForModule(moduleAlias);
        if (variants.isEmpty()) return Set.of();
        var required = EnumSet.noneOf(EntityCapability.class);
        for (var revision : list(Criteria.of().in("variantId", variants).eq("status", PlatformPresentationRevisionStatus.PUBLISHED)
                .in("templateVersion", java.util.List.of(PlatformPresentationTemplateCatalog.MODE_AWARE_VERSION,
                        PlatformPresentationTemplateCatalog.MODE_AWARE_ACTION_VERSION)),
                new PageRequest(0, Integer.MAX_VALUE))) {
            try {
                String mode = JSON.readTree(revision.getUiTreeJson()).path("mode").asText();
                if ("TREE_CARD".equals(mode)) required.add(EntityCapability.TREE);
                if ("TREE_CARD".equals(mode) || "MICRO_LIST_CARD".equals(mode)) required.add(EntityCapability.SORT);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Published page mode cannot be resolved", exception);
            }
        }
        return Set.copyOf(required);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, PlatformPresentationRevision.class,
                java.util.List.of("id", "variantId", "revisionNo", "templateAlias", "templateVersion", "status",
                        "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("revisionNo"));
    }

    /**
     * Resolves one revision through the same visibility boundary as its parent variant.
     * Callers that address a revision below a variant must use this method instead of treating
     * a revision id as a globally visible resource.
     */
    public PlatformPresentationRevision requireVisibleRevision(String variantId, String revisionId) {
        PlatformPresentationVariant variant = variantService.requireVisibleVariant(variantId);
        PlatformPresentationRevision revision = revisionId == null || revisionId.isBlank() ? null : select(revisionId);
        if (revision == null && revisionId != null && !revisionId.isBlank()
                && TenantContext.currentTenantId().isPresent()) {
            try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("resolve global presentation revision")) {
                revision = select(revisionId);
            }
        }
        if (revision == null || !Objects.equals(variant.getId(), revision.getVariantId())) {
            throw BusinessExceptions.warning("platform.presentation-revision.not-found",
                    "Presentation revision does not belong to variant: " + variantId + "." + revisionId);
        }
        return revision;
    }

    @Override
    public void beforeInsert(PlatformPresentationRevision revision) {
        normalizeAndValidate(revision);
        rejectDirectStatusTransition(null, revision);
    }

    @Override
    public void beforeUpdate(PlatformPresentationRevision revision) {
        PlatformPresentationRevision existing = selectIncludingDeleted(revision.getId());
        rejectPublishedContentMutation(existing, revision);
        normalizeAndValidate(revision);
        rejectDirectStatusTransition(existing, revision);
        rejectChanged(existing, revision, "Presentation revision variant", PlatformPresentationRevision::getVariantId);
        rejectChanged(existing, revision, "Presentation revision number", PlatformPresentationRevision::getRevisionNo);
    }

    @Override
    public void afterUpdate(PlatformPresentationRevision revision, int updated) {
        refreshPublishedPageExecution(revision);
    }

    @Override
    public void afterDelete(String id, PlatformPresentationRevision revision, int deleted) {
        refreshPublishedPageExecution(revision);
    }

    private void normalizeAndValidate(PlatformPresentationRevision revision) {
        PlatformPresentationVariant variant = variantService.requireVisibleVariant(revision.getVariantId());
        revision.setVariantId(variant.getId());
        if (revision.getRevisionNo() == null || revision.getRevisionNo() <= 0) {
            throw BusinessExceptions.warning("platform.presentation-revision.number-invalid",
                    "Presentation revisionNo must be positive");
        }
        revision.setTemplateAlias(PlatformNameRules.requireIdentifier(revision.getTemplateAlias(), "templateAlias"));
        if (revision.getTemplateVersion() == null || revision.getTemplateVersion() <= 0) {
            throw BusinessExceptions.warning("platform.presentation-revision.template-version-invalid",
                    "Presentation revision templateVersion must be positive");
        }
        if (revision.getStatus() == null) {
            revision.setStatus(PlatformPresentationRevisionStatus.DRAFT);
        }
        if (revision.getTitle() == null || revision.getTitle().isBlank()) {
            revision.setTitle(variant.getTitle() + " v" + revision.getRevisionNo());
        }
        rejectDuplicate(revision, Criteria.of().eq("variantId", variant.getId()).eq("revisionNo", revision.getRevisionNo()),
                "Presentation revision number must be unique in variant");
        if (revision.getStatus() == PlatformPresentationRevisionStatus.PUBLISHED) {
            rejectDuplicate(revision, Criteria.of().eq("variantId", variant.getId())
                            .eq("status", PlatformPresentationRevisionStatus.PUBLISHED),
                    "Only one presentation revision can be published in a variant");
        }
    }

    private void rejectPublishedContentMutation(PlatformPresentationRevision existing,
                                                PlatformPresentationRevision incoming) {
        if (existing == null || existing.getStatus() != PlatformPresentationRevisionStatus.PUBLISHED) {
            return;
        }
        if (!Objects.equals(existing.getTemplateAlias(), incoming.getTemplateAlias())
                || !Objects.equals(existing.getTemplateVersion(), incoming.getTemplateVersion())
                || !Objects.equals(existing.getUiTreeJson(), incoming.getUiTreeJson())) {
            throw BusinessExceptions.warning("platform.presentation-revision.published-content-mutation-denied",
                    "Published presentation revision template and UI tree cannot be changed; create a draft revision");
        }
    }

    private void rejectDirectStatusTransition(PlatformPresentationRevision existing,
                                              PlatformPresentationRevision incoming) {
        if (incoming == null || PlatformPresentationRevisionPublishContext.active()) {
            return;
        }
        if (existing != null && !Objects.equals(existing.getStatus(), incoming.getStatus())) {
            throw BusinessExceptions.warning("platform.presentation-revision.direct-status-transition-denied",
                    "Presentation revision status can only be changed through presentation revision publish service: "
                            + incoming.getId());
        }
        if (existing != null || incoming.getStatus() != PlatformPresentationRevisionStatus.PUBLISHED) {
            return;
        }
        throw BusinessExceptions.warning("platform.presentation-revision.direct-publish-denied",
                "Presentation revision can only be published through presentation revision publish service: "
                        + incoming.getId());
    }

    private void refreshPublishedPageExecution(PlatformPresentationRevision revision) {
        if (revision == null || revision.getStatus() != PlatformPresentationRevisionStatus.PUBLISHED
                || PlatformPresentationRevisionPublishContext.active()) {
            return;
        }
        pageExecutionCoordinator.prepareAfterPublishedConfigurationChange(
                variantService.moduleAliasForVariant(revision.getVariantId()));
    }
}
