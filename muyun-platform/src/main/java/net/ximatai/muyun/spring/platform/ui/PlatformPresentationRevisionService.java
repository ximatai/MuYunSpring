package net.ximatai.muyun.spring.platform.ui;

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

    private final PlatformPresentationVariantService variantService;

    public PlatformPresentationRevisionService(BaseDao<PlatformPresentationRevision, String> revisionDao,
                                               PlatformPresentationVariantService variantService) {
        super(MODULE_ALIAS, PlatformPresentationRevision.class, revisionDao);
        this.variantService = variantService;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, PlatformPresentationRevision.class,
                java.util.List.of("id", "variantId", "revisionNo", "templateAlias", "templateVersion", "status",
                        "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("revisionNo"));
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
}
