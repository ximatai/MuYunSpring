package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.query.QueryLikePattern;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebPageResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Candidate navigation for the role owner-scope workspace.
 *
 * <p>The browser can only move through keys produced here: {@value #TENANT_LIST_NAVIGATION_KEY}
 * lists visible tenants and {@code tenant:&lt;id&gt;} lists organizations below one authorized
 * tenant.  This is deliberately role-workspace specific; it is not a general cross-tenant
 * reference protocol.  The final selection is still resolved by
 * {@link RoleScopePageSelectionResolver} for every query and mutation.</p>
 */
@Component
public final class RoleScopeSelectionFacade {
    static final String TENANT_LIST_NAVIGATION_KEY = "role-scope-tenants";
    static final int MAXIMUM_NAVIGATION_KEY_LENGTH = 256;
    static final int MAXIMUM_KEYWORD_LENGTH = 128;
    static final int MAXIMUM_PARENT_ID_LENGTH = 128;
    private static final String PLATFORM_SELECTION_KEY = "platform";
    private static final String TENANT_PREFIX = "tenant:";

    private final TenantService tenantService;
    private final OrganizationService organizationService;

    public RoleScopeSelectionFacade(TenantService tenantService, OrganizationService organizationService) {
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.organizationService = Objects.requireNonNull(organizationService, "organizationService must not be null");
    }

    public RoleScopeSelectionDescriptor descriptor() {
        CurrentUser user = requireAuthorizedRoleQuery();
        if (user.system()) {
            return new RoleScopeSelectionDescriptor(
                    List.of(new DirectSelection(PLATFORM_SELECTION_KEY, "平台角色")),
                    List.of(new Navigation(TENANT_LIST_NAVIGATION_KEY, ScopeLevel.TENANT, "租户")));
        }
        String tenantId = requireTenantUserTenant(user);
        tenantService.requireActiveTenant(tenantId);
        return new RoleScopeSelectionDescriptor(
                List.of(new DirectSelection(TENANT_PREFIX + tenantId, "当前租户")),
                List.of(new Navigation(TENANT_PREFIX + tenantId, ScopeLevel.ORGANIZATION, "机构")));
    }

    public WebPageResponse<Candidate> candidates(CandidateRequest request) {
        CurrentUser user = requireAuthorizedRoleQuery();
        CandidateRequest normalized = request == null ? CandidateRequest.EMPTY : request;
        ScopeLevel level = ScopeLevel.require(normalized.level());
        WebPageRequest page = normalized.pageOrDefault();
        return switch (level) {
            case TENANT -> tenantCandidates(user, normalized, page);
            case ORGANIZATION -> organizationCandidates(user, normalized, page);
        };
    }

    private WebPageResponse<Candidate> tenantCandidates(CurrentUser user,
                                                         CandidateRequest request,
                                                         WebPageRequest page) {
        if (!user.system() || !TENANT_LIST_NAVIGATION_KEY.equals(request.navigationKey())) {
            throw denied("role scope tenant navigation is not available to the current user");
        }
        Criteria criteria = tenantService.enabledCriteria(keywordCriteria(Criteria.of(), request.keyword(), "title", "id"));
        PageResult<Tenant> result = tenantService.pageQuery(criteria, pageRequest(page), Sort.asc("title"));
        return page(result, tenant -> new Candidate(tenant.getId(), tenant.getTitle(), tenant.getId(),
                TENANT_PREFIX + tenant.getId(), false));
    }

    private WebPageResponse<Candidate> organizationCandidates(CurrentUser user,
                                                               CandidateRequest request,
                                                               WebPageRequest page) {
        String tenantId = tenantIdFromNavigationKey(request.navigationKey(), user);
        tenantService.requireActiveTenant(tenantId);
        try (TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            String parentId = normalizedParentId(request.parentId());
            if (!TreeAbility.ROOT_ID.equals(parentId)) {
                Organization parent = organizationService.requireEnabled(parentId,
                        "role scope organization is not active: " + parentId);
                if (!tenantId.equals(parent.getTenantId())) {
                    throw denied("role scope organization parent does not belong to the selected tenant");
                }
            }
            Criteria criteria = Criteria.of().eq("tenantId", tenantId).eq("parentId", parentId);
            criteria = organizationService.enabledCriteria(keywordCriteria(criteria, request.keyword(), "title", "code"));
            PageResult<Organization> result = organizationService.pageQuery(criteria, pageRequest(page),
                    Sort.asc("sortOrder"), Sort.asc("title"));
            Set<String> expandableIds = visibleOrganizationParents(tenantId, result.getRecords());
            return page(result, organization -> new Candidate(organization.getId(), organization.getTitle(),
                    organization.getCode(), "organization:" + organization.getId(),
                    expandableIds.contains(organization.getId())));
        }
    }

    /**
     * Resolve only a one-row child presence check for each delivered organization.  The entity
     * DAO has no grouped parent-id projection, and reading all children merely to calculate an
     * expand icon would make one large branch unbounded.  Each check retains the tenant and
     * enabled predicates used for the tree page itself.
     */
    private Set<String> visibleOrganizationParents(String tenantId, List<Organization> organizations) {
        List<String> ids = organizations == null ? List.of() : organizations.stream()
                .map(Organization::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (ids.isEmpty()) return Set.of();
        return ids.stream().filter(parentId -> {
            Criteria childrenCriteria = Criteria.of().eq("tenantId", tenantId).eq("parentId", parentId);
            childrenCriteria = organizationService.enabledCriteria(childrenCriteria);
            return !organizationService.pageQuery(childrenCriteria, PageRequest.of(1, 1)).getRecords().isEmpty();
        }).collect(Collectors.toUnmodifiableSet());
    }

    private String tenantIdFromNavigationKey(String navigationKey, CurrentUser user) {
        if (navigationKey == null || !navigationKey.startsWith(TENANT_PREFIX)) {
            throw new IllegalArgumentException("role scope organization navigation key is invalid");
        }
        String tenantId = navigationKey.substring(TENANT_PREFIX.length());
        if (tenantId.isBlank() || tenantId.indexOf(':') >= 0) {
            throw new IllegalArgumentException("role scope organization navigation key is invalid");
        }
        if (!user.system() && !tenantId.equals(requireTenantUserTenant(user))) {
            throw denied("role scope tenant is not visible to the current user");
        }
        return tenantId;
    }

    private Criteria keywordCriteria(Criteria criteria, String keyword, String... fields) {
        if (keyword == null || keyword.isBlank()) return criteria;
        Criteria matched = Criteria.of();
        for (String field : fields) {
            matched.orGroup(Criteria.of().like(field, QueryLikePattern.containsLiteral(keyword.trim())).getRoot());
        }
        return criteria.andGroup(matched.getRoot());
    }

    private String normalizedParentId(String parentId) {
        return parentId == null || parentId.isBlank() ? TreeAbility.ROOT_ID : parentId.trim();
    }

    private PageRequest pageRequest(WebPageRequest page) {
        return PageRequest.of(page.pageNum(), page.pageSize());
    }

    private <T> WebPageResponse<Candidate> page(PageResult<T> result,
                                                 java.util.function.Function<T, Candidate> mapper) {
        return new WebPageResponse<>(result.getRecords().stream().map(mapper).toList(), result.getTotal(),
                result.getPageNum(), result.getPageSize(), result.getPages(), result.isTotalKnown(), null);
    }

    private CurrentUser requireAuthorizedRoleQuery() {
        CurrentUser currentUser = CurrentUserContext.currentUser()
                .orElseThrow(() -> denied("role scope selection requires a current user"));
        ActionExecutionContext context = ActionExecutionContextHolder.current()
                .orElseThrow(() -> denied("role scope selection requires an authorized action context"));
        if (!RoleService.MODULE_ALIAS.equals(context.moduleAlias())
                || context.platformAction() != PlatformAction.QUERY
                || context.authorizationResult() == null
                || context.currentUser().filter(user -> user.userId().equals(currentUser.userId())).isEmpty()) {
            throw denied("role scope selection action context does not match the request");
        }
        return currentUser;
    }

    private String requireTenantUserTenant(CurrentUser user) {
        String tenantId = user.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw denied("tenant role scope selection requires a tenant identity");
        }
        return tenantId;
    }

    private PlatformAccessDeniedException denied(String message) {
        return new PlatformAccessDeniedException(message);
    }

    public enum ScopeLevel {
        TENANT,
        ORGANIZATION;

        static ScopeLevel require(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("role scope candidate level must not be blank");
            }
            try {
                return ScopeLevel.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("unsupported role scope candidate level: " + value, exception);
            }
        }
    }

    public record RoleScopeSelectionDescriptor(List<DirectSelection> directSelections,
                                               List<Navigation> navigations) {
        public RoleScopeSelectionDescriptor {
            directSelections = directSelections == null ? List.of() : List.copyOf(directSelections);
            navigations = navigations == null ? List.of() : List.copyOf(navigations);
        }
    }

    public record DirectSelection(String selectionKey, String title) {
    }

    public record Navigation(String navigationKey, ScopeLevel level, String title) {
    }

    public record CandidateRequest(String navigationKey, String level, String keyword, String parentId,
                                   WebPageRequest page) {
        static final CandidateRequest EMPTY = new CandidateRequest(null, null, null, null, null);

        public CandidateRequest {
            navigationKey = boundedText(navigationKey, "navigationKey", MAXIMUM_NAVIGATION_KEY_LENGTH);
            level = boundedText(level, "level", 32);
            keyword = boundedText(keyword, "keyword", MAXIMUM_KEYWORD_LENGTH);
            parentId = boundedText(parentId, "parentId", MAXIMUM_PARENT_ID_LENGTH);
        }

        WebPageRequest pageOrDefault() {
            return page == null ? WebPageRequest.DEFAULT : page;
        }
    }

    private static String boundedText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("role scope candidate " + name + " must not exceed "
                    + maximumLength + " characters");
        }
        return normalized;
    }

    public record Candidate(String id, String title, String subtitle, String selectionKey, boolean expandable) {
    }
}
