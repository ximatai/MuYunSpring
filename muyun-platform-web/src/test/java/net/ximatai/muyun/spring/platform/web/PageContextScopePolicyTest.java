package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageContextScopePolicyTest {
    @Test
    void shouldFailClosedWhenRequiredNavigatorListScopeIsMissing() {
        PageContextBindingDefinition binding = PageContextBindingDefinition.navigatorList(
                "scheme", "schemeId", NavigatorListQueryMode.REQUIRED_SCOPE);

        assertThatThrownBy(() -> PageContextScopePolicy.criteria(List.of(binding), Map.of(), false))
                .hasMessage("Page navigator scope is required: scheme");
    }

    @Test
    void shouldFailClosedWhenNavigatorReferenceDependsOnAnUnselectedParent() {
        PageContextBindingDefinition binding = PageContextBindingDefinition.navigatorToNavigator(
                "tenant", "project", "tenantId");

        assertThatThrownBy(() -> PageContextScopePolicy.criteria(List.of(binding), Map.of(), true))
                .hasMessage("Page navigator scope is required: tenant");
    }

    @Test
    void shouldLeaveOptionalNavigatorListFilterAbsent() {
        PageContextBindingDefinition binding = PageContextBindingDefinition.navigatorList(
                "project", "projectId", NavigatorListQueryMode.OPTIONAL_FILTER);

        assertThat(PageContextScopePolicy.criteria(List.of(binding), Map.of(), false).isEmpty()).isTrue();
        assertThat(PageContextScopePolicy.recordScopeBindings(List.of(binding))).isEmpty();
    }

    @Test
    void shouldResolveAnOpaqueSelectionThroughTheServerRegistry() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("X-MuYun-Page-Selection", "{\"kind\":\"roleScope\",\"key\":\"platform\"}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        PageSelectionContextResolver resolver = new PageSelectionContextResolver() {
            @Override public String selectionKind() { return "roleScope"; }

            @Override public ResolvedPageSelectionContext resolve(PageSelectionContextRequest request) {
                assertThat(request.moduleAlias()).isEqualTo("iam.role");
                assertThat(request.action()).isEqualTo(PlatformAction.QUERY);
                assertThat(request.selectionKey()).isEqualTo("platform");
                return new ResolvedPageSelectionContext("roleScope", "platform",
                        Map.of("ownerScopeKey", PageContextValue.of("platform")));
            }
        };
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"))) {
            assertThat(PageContextScopePolicy.criteria(List.of(PageContextBindingDefinition.resolvedSelection(
                    "roleScope", PageContextTarget.LIST_QUERY, "ownerScopeKey")), Map.of(), false,
                    "iam.role", PlatformAction.QUERY,
                    new PageSelectionContextResolverRegistry(List.of(resolver))).isEmpty()).isFalse();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void shouldRejectMissingOrWrongSelectionReference() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("X-MuYun-Page-Selection", "{\"kind\":\"anotherScope\",\"key\":\"platform\"}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        try {
            assertThatThrownBy(() -> PageContextScopePolicy.criteria(List.of(PageContextBindingDefinition.resolvedSelection(
                    "roleScope", PageContextTarget.LIST_QUERY, "ownerScopeKey")), Map.of(), false,
                    "iam.role", PlatformAction.QUERY, new PageSelectionContextResolverRegistry(List.of())))
                    .hasMessage("Page navigator scope is required: roleScope");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void shouldReResolveSelectionForRecordAccessAndRejectOutOfScopeRecords() {
        MockHttpServletRequest servletRequest = selectionRequest("organization-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        ScopeRecord record = new ScopeRecord("organization-2", "untrusted");
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"))) {
            assertThatThrownBy(() -> PageContextScopePolicy.requireRecordInScope(record, List.of(
                    PageContextBindingDefinition.resolvedSelection("roleScope", PageContextTarget.LIST_QUERY,
                            "ownerScopeId")), "iam.role", PlatformAction.VIEW, resolver()))
                    .hasMessage("Record does not belong to the current page scope: ownerScopeId");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void shouldStampSelectionScopeAndPreservePresentNullForPlatformRange() {
        MockHttpServletRequest servletRequest = selectionRequest("platform");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        ScopeRecord record = new ScopeRecord("untrusted", "untrusted");
        PageSelectionContextResolver resolver = new PageSelectionContextResolver() {
            @Override public String selectionKind() { return "roleScope"; }

            @Override public ResolvedPageSelectionContext resolve(PageSelectionContextRequest request) {
                assertThat(request.action()).isEqualTo(PlatformAction.CREATE);
                return new ResolvedPageSelectionContext("roleScope", "platform", Map.of(
                        "ownerScopeType", PageContextValue.of("PLATFORM"),
                        "ownerScopeId", PageContextValue.of(null)));
            }
        };
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"))) {
            PageContextScopePolicy.applyForCreate(record, List.of(
                    PageContextBindingDefinition.resolvedSelection("roleScope", PageContextTarget.LIST_QUERY,
                            "ownerScopeType"),
                    PageContextBindingDefinition.resolvedSelection("roleScope", PageContextTarget.LIST_QUERY,
                            "ownerScopeId")), "iam.role", PlatformAction.CREATE,
                    new PageSelectionContextResolverRegistry(List.of(resolver)));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        assertThat(record.ownerScopeType).isEqualTo("PLATFORM");
        assertThat(record.ownerScopeId).isNull();
    }

    @Test
    void shouldExposeOnlyServerResolvedSelectionFieldsForFormDefaults() {
        MockHttpServletRequest servletRequest = selectionRequest("organization-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"))) {
            assertThat(PageContextScopePolicy.resolvedSelectionValues(List.of(
                    PageContextBindingDefinition.resolvedSelection("roleScope", PageContextTarget.FORM_DEFAULT,
                            "ownerScopeType"),
                    PageContextBindingDefinition.resolvedSelection("roleScope", PageContextTarget.FORM_DEFAULT,
                            "ownerScopeId")), PageContextTarget.FORM_DEFAULT, "iam.role", PlatformAction.CREATE,
                    new PageSelectionContextResolverRegistry(List.of(new PageSelectionContextResolver() {
                        @Override public String selectionKind() { return "roleScope"; }

                        @Override public ResolvedPageSelectionContext resolve(PageSelectionContextRequest request) {
                            assertThat(request.action()).isEqualTo(PlatformAction.CREATE);
                            return new ResolvedPageSelectionContext("roleScope", "organization-1", Map.of(
                                    "ownerScopeType", PageContextValue.of("organization"),
                                    "ownerScopeId", PageContextValue.of("organization-1")));
                        }
                    }))))
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "ownerScopeType", "organization", "ownerScopeId", "organization-1"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private static MockHttpServletRequest selectionRequest(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-MuYun-Page-Selection", "{\"kind\":\"roleScope\",\"key\":\"" + key + "\"}");
        return request;
    }

    private static PageSelectionContextResolverRegistry resolver() {
        return new PageSelectionContextResolverRegistry(List.of(new PageSelectionContextResolver() {
            @Override public String selectionKind() { return "roleScope"; }

            @Override public ResolvedPageSelectionContext resolve(PageSelectionContextRequest request) {
                assertThat(request.action()).isEqualTo(PlatformAction.VIEW);
                return new ResolvedPageSelectionContext("roleScope", "organization-1", Map.of(
                        "ownerScopeId", PageContextValue.of("organization-1")));
            }
        }));
    }

    static final class ScopeRecord {
        private String ownerScopeId;
        private String ownerScopeType;

        ScopeRecord(String ownerScopeId, String ownerScopeType) {
            this.ownerScopeId = ownerScopeId;
            this.ownerScopeType = ownerScopeType;
        }

        public String getOwnerScopeId() { return ownerScopeId; }

        public void setOwnerScopeId(String ownerScopeId) { this.ownerScopeId = ownerScopeId; }

        public String getOwnerScopeType() { return ownerScopeType; }

        public void setOwnerScopeType(String ownerScopeType) { this.ownerScopeType = ownerScopeType; }
    }
}
