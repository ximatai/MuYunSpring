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
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;

/** Owns the scope invariant of a client-specific page composition. */
@Service
public class PlatformPresentationVariantService extends AbstractAbilityService<PlatformPresentationVariant> implements
        SoftDeleteAbility<PlatformPresentationVariant>,
        EnableAbility<PlatformPresentationVariant>,
        SortAbility<PlatformPresentationVariant>,
        QueryAbility<PlatformPresentationVariant> {
    public static final String MODULE_ALIAS = "platform.presentation_variant";

    private final PlatformPageDefinitionService pageService;

    public PlatformPresentationVariantService(BaseDao<PlatformPresentationVariant, String> variantDao,
                                              PlatformPageDefinitionService pageService) {
        super(MODULE_ALIAS, PlatformPresentationVariant.class, variantDao);
        this.pageService = pageService;
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, PlatformPresentationVariant.class,
                java.util.List.of("id", "pageId", "clientType", "scopeType", "tenantId", "organizationId",
                        "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"));
    }

    @Override
    public void beforeInsert(PlatformPresentationVariant variant) {
        normalizeAndValidate(variant);
    }

    @Override
    public void beforeUpdate(PlatformPresentationVariant variant) {
        PlatformPresentationVariant existing = selectIncludingDeleted(variant.getId());
        normalizeAndValidate(variant);
        rejectChanged(existing, variant, "Presentation variant page", PlatformPresentationVariant::getPageId);
        rejectChanged(existing, variant, "Presentation variant client", PlatformPresentationVariant::getClientType);
        rejectChanged(existing, variant, "Presentation variant scope", PlatformPresentationVariant::getScopeType);
        rejectChanged(existing, variant, "Presentation variant tenant", PlatformPresentationVariant::getTenantId);
        rejectChanged(existing, variant, "Presentation variant organization", PlatformPresentationVariant::getOrganizationId);
    }

    private void normalizeAndValidate(PlatformPresentationVariant variant) {
        PlatformPageDefinition page = pageService.requireVisiblePage(variant.getPageId());
        variant.setPageId(page.getId());
        if (variant.getClientType() == null) {
            variant.setClientType(PlatformPresentationClientType.WEB);
        }
        if (variant.getScopeType() == null) {
            variant.setScopeType(PlatformPresentationScopeType.GLOBAL);
        }
        normalizeScope(variant);
        if (variant.getTitle() == null || variant.getTitle().isBlank()) {
            variant.setTitle(page.getTitle() + "-" + variant.getClientType().name());
        }
        rejectDuplicate(variant, Criteria.of()
                        .eq("pageId", page.getId())
                        .eq("clientType", variant.getClientType())
                        .eq("scopeType", variant.getScopeType())
                        .eqNullable(StandardEntitySchema.TENANT_ID_FIELD, variant.getTenantId())
                        .eqNullable("organizationId", variant.getOrganizationId()),
                "Only one presentation variant is allowed for page, client and scope");
    }

    public PlatformPresentationVariant requireVisibleVariant(String id) {
        PlatformPresentationVariant variant = id == null || id.isBlank() ? null : select(id);
        if (variant == null && id != null && !id.isBlank() && TenantContext.currentTenantId().isPresent()) {
            try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("resolve global presentation variant")) {
                variant = select(id);
            }
        }
        if (variant == null) {
            throw BusinessExceptions.warning("platform.presentation-variant.not-found",
                    "Presentation revision requires existing variant: " + id);
        }
        return variant;
    }

    private void normalizeScope(PlatformPresentationVariant variant) {
        switch (variant.getScopeType()) {
            case GLOBAL -> {
                if (!TenantContext.isSystem()) {
                    throw BusinessExceptions.warning("platform.presentation-variant.global-system-context-required",
                            "Global presentation variant requires system context");
                }
                variant.setTenantId(null);
                variant.setOrganizationId(null);
            }
            case TENANT -> {
                if (variant.getTenantId() == null || variant.getTenantId().isBlank()) {
                    throw BusinessExceptions.warning("platform.presentation-variant.tenant-required",
                            "Tenant presentation variant requires tenantId");
                }
                variant.setTenantId(variant.getTenantId().trim());
                variant.setOrganizationId(null);
            }
            case ORGANIZATION -> {
                if (variant.getTenantId() == null || variant.getTenantId().isBlank()) {
                    throw BusinessExceptions.warning("platform.presentation-variant.organization-tenant-required",
                            "Organization presentation variant requires tenantId");
                }
                if (variant.getOrganizationId() == null || variant.getOrganizationId().isBlank()) {
                    throw BusinessExceptions.warning("platform.presentation-variant.organization-required",
                            "Organization presentation variant requires organizationId");
                }
                variant.setTenantId(variant.getTenantId().trim());
                variant.setOrganizationId(variant.getOrganizationId().trim());
            }
        }
    }
}
