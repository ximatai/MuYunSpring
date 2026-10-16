package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
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
import org.springframework.beans.factory.ObjectProvider;
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
    private final PublishedPageExecutionCoordinator pageExecutionCoordinator;

    public PlatformPresentationVariantService(BaseDao<PlatformPresentationVariant, String> variantDao,
                                              PlatformPageDefinitionService pageService) {
        this(variantDao, pageService, PublishedPageExecutionCoordinator.noop());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PlatformPresentationVariantService(BaseDao<PlatformPresentationVariant, String> variantDao,
                                              PlatformPageDefinitionService pageService,
                                              ObjectProvider<PublishedPageExecutionCoordinator> pageExecutionCoordinator) {
        this(variantDao, pageService, pageExecutionCoordinator == null ? PublishedPageExecutionCoordinator.noop()
                : pageExecutionCoordinator.getIfAvailable(PublishedPageExecutionCoordinator::noop));
    }

    PlatformPresentationVariantService(BaseDao<PlatformPresentationVariant, String> variantDao,
                                       PlatformPageDefinitionService pageService,
                                       PublishedPageExecutionCoordinator pageExecutionCoordinator) {
        super(MODULE_ALIAS, PlatformPresentationVariant.class, variantDao);
        this.pageService = pageService;
        this.pageExecutionCoordinator = pageExecutionCoordinator == null
                ? PublishedPageExecutionCoordinator.noop() : pageExecutionCoordinator;
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
        rejectChangedText(existing, variant, TextReference.code(
                "platform.rejection.platform-presentation-variant.presentation-variant-page-cannot-be-changed",
                "Presentation variant page cannot be changed"), PlatformPresentationVariant::getPageId);
        rejectChangedText(existing, variant, TextReference.code(
                "platform.rejection.platform-presentation-variant.presentation-variant-client-cannot-be-changed",
                "Presentation variant client cannot be changed"), PlatformPresentationVariant::getClientType);
        rejectChangedText(existing, variant, TextReference.code(
                "platform.rejection.platform-presentation-variant.presentation-variant-scope-cannot-be-changed",
                "Presentation variant scope cannot be changed"), PlatformPresentationVariant::getScopeType);
        rejectChangedText(existing, variant, TextReference.code(
                "platform.rejection.platform-presentation-variant.presentation-variant-tenant-cannot-be-changed",
                "Presentation variant tenant cannot be changed"), PlatformPresentationVariant::getTenantId);
        rejectChangedText(existing, variant, TextReference.code(
                "platform.rejection.platform-presentation-variant.presentation-variant-organization-cannot-be-changed",
                "Presentation variant organization cannot be changed"), PlatformPresentationVariant::getOrganizationId);
    }

    @Override
    public void afterUpdate(PlatformPresentationVariant variant, int updated) {
        refreshPublishedPageExecution(variant);
    }

    @Override
    public void afterDelete(String id, PlatformPresentationVariant variant, int deleted) {
        refreshPublishedPageExecution(variant);
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
                        .eqNullable("organizationId", variant.getOrganizationId()), () -> PlatformErrors.badRequestText(TextReference.code(
                                "platform.rejection.platform-presentation-variant.only-one-presentation-variant-is-allowed-for-page-client-and-scope",
                                "Only one presentation variant is allowed for page, client and scope")));
    }

    public PlatformPresentationVariant requireVisibleVariant(String id) {
        PlatformPresentationVariant variant = id == null || id.isBlank() ? null : select(id);
        if (variant == null && id != null && !id.isBlank() && TenantContext.currentTenantId().isPresent()) {
            try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("resolve global presentation variant")) {
                variant = select(id);
            }
        }
        if (variant == null) {
            throw BusinessExceptions.warningText("platform.presentation-variant.not-found", TextReference.code(
                    "platform.rejection.platform-presentation-variant.presentation-revision-requires-existing-variant",
                    "Presentation revision requires existing variant: {value1}"), Map.of("value1", String.valueOf(id)));
        }
        return variant;
    }

    java.util.List<String> variantIdsForModule(String moduleAlias) {
        var all = new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE);
        var pageIds = pageService.list(Criteria.of().eq("moduleAlias", moduleAlias), all).stream()
                .map(PlatformPageDefinition::getId).toList();
        return pageIds.isEmpty() ? java.util.List.of() : list(Criteria.of().in("pageId", pageIds), all).stream()
                .map(PlatformPresentationVariant::getId).toList();
    }

    String moduleAliasForVariant(String variantId) {
        PlatformPresentationVariant variant = requireVisibleVariant(variantId);
        return pageService.requireVisiblePage(variant.getPageId()).getModuleAlias();
    }

    private void normalizeScope(PlatformPresentationVariant variant) {
        switch (variant.getScopeType()) {
            case GLOBAL -> {
                if (!TenantContext.isSystem()) {
                    throw BusinessExceptions.warningText("platform.presentation-variant.global-system-context-required", TextReference.code(
                            "platform.rejection.platform-presentation-variant.global-presentation-variant-requires-system-context",
                            "Global presentation variant requires system context"));
                }
                variant.setTenantId(null);
                variant.setOrganizationId(null);
            }
            case TENANT -> {
                if (variant.getTenantId() == null || variant.getTenantId().isBlank()) {
                    throw BusinessExceptions.warningText("platform.presentation-variant.tenant-required", TextReference.code(
                            "platform.rejection.platform-presentation-variant.tenant-presentation-variant-requires-tenantid",
                            "Tenant presentation variant requires tenantId"));
                }
                variant.setTenantId(variant.getTenantId().trim());
                variant.setOrganizationId(null);
            }
            case ORGANIZATION -> {
                if (variant.getTenantId() == null || variant.getTenantId().isBlank()) {
                    throw BusinessExceptions.warningText("platform.presentation-variant.organization-tenant-required", TextReference.code(
                            "platform.rejection.platform-presentation-variant.organization-presentation-variant-requires-tenantid",
                            "Organization presentation variant requires tenantId"));
                }
                if (variant.getOrganizationId() == null || variant.getOrganizationId().isBlank()) {
                    throw BusinessExceptions.warningText("platform.presentation-variant.organization-required", TextReference.code(
                            "platform.rejection.platform-presentation-variant.organization-presentation-variant-requires-organizationid",
                            "Organization presentation variant requires organizationId"));
                }
                variant.setTenantId(variant.getTenantId().trim());
                variant.setOrganizationId(variant.getOrganizationId().trim());
            }
        }
    }

    private void refreshPublishedPageExecution(PlatformPresentationVariant variant) {
        if (PlatformPresentationRevisionPublishContext.active()
                || variant == null || variant.getPageId() == null || variant.getPageId().isBlank()) return;
        PlatformPageDefinition page = pageService.requireVisiblePage(variant.getPageId());
        pageExecutionCoordinator.prepareAfterPublishedConfigurationChange(page.getModuleAlias());
    }
}
