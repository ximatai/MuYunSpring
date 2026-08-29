package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Resolves the one effective published presentation revision for a page request.
 *
 * <p>A presentation is a complete scope-specific definition, so resolution chooses the first
 * usable scope in the explicit order organization, tenant, then global. Organization matching is
 * deliberately exact here; organization-tree inheritance is a separate policy and must not be
 * implied by this resolver.</p>
 */
@Service
public class PlatformPresentationRevisionResolver {
    private final PlatformPresentationVariantService variantService;
    private final PlatformPresentationRevisionService revisionService;

    public PlatformPresentationRevisionResolver(PlatformPresentationVariantService variantService,
                                                PlatformPresentationRevisionService revisionService) {
        this.variantService = variantService;
        this.revisionService = revisionService;
    }

    public Optional<PlatformPresentationRevision> resolve(String pageId,
                                                           PlatformPresentationClientType clientType,
                                                           String tenantId,
                                                           String organizationId) {
        if (isBlank(pageId) || clientType == null) {
            return Optional.empty();
        }
        String normalizedPageId = pageId.trim();
        String normalizedTenantId = normalize(tenantId);
        String normalizedOrganizationId = normalize(organizationId);
        try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter(
                "resolve effective published presentation revision")) {
            for (PlatformPresentationScopeType scope : resolutionScopes(normalizedTenantId, normalizedOrganizationId)) {
                Optional<PlatformPresentationRevision> resolved = resolveScope(
                        normalizedPageId, clientType, scope, normalizedTenantId, normalizedOrganizationId);
                if (resolved.isPresent()) {
                    return resolved;
                }
            }
        }
        return Optional.empty();
    }

    private List<PlatformPresentationScopeType> resolutionScopes(String tenantId, String organizationId) {
        if (tenantId == null) {
            return List.of(PlatformPresentationScopeType.GLOBAL);
        }
        if (organizationId == null) {
            return List.of(PlatformPresentationScopeType.TENANT, PlatformPresentationScopeType.GLOBAL);
        }
        return List.of(PlatformPresentationScopeType.ORGANIZATION, PlatformPresentationScopeType.TENANT,
                PlatformPresentationScopeType.GLOBAL);
    }

    private Optional<PlatformPresentationRevision> resolveScope(String pageId,
                                                                 PlatformPresentationClientType clientType,
                                                                 PlatformPresentationScopeType scopeType,
                                                                 String tenantId,
                                                                 String organizationId) {
        Criteria variantCriteria = Criteria.of()
                .eq("pageId", pageId)
                .eq("clientType", clientType)
                .eq("scopeType", scopeType)
                .eqNullable("tenantId", scopeType == PlatformPresentationScopeType.GLOBAL ? null : tenantId)
                .eqNullable("organizationId", scopeType == PlatformPresentationScopeType.ORGANIZATION
                        ? organizationId : null);
        PlatformPresentationVariant variant = variantService.list(variantService.enabledCriteria(variantCriteria))
                .stream()
                .findFirst()
                .orElse(null);
        if (variant == null) {
            return Optional.empty();
        }
        return revisionService.list(revisionService.enabledCriteria(Criteria.of()
                        .eq("variantId", variant.getId())
                        .eq("status", PlatformPresentationRevisionStatus.PUBLISHED)))
                .stream()
                .findFirst();
    }

    private String normalize(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
