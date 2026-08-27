package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class PageSelectionContextResolverRegistryTest {
    @Test
    void resolvesOnlyTheRegisteredKindAndPreservesAnExplicitNull() {
        PageSelectionContextResolver resolver = new PageSelectionContextResolver() {
            @Override public String selectionKind() { return "roleScope"; }
            @Override public ResolvedPageSelectionContext resolve(PageSelectionContextRequest request) {
                return new ResolvedPageSelectionContext("roleScope", request.selectionKey(),
                        Map.of("ownerScopeType", PageContextValue.of("PLATFORM"),
                                "ownerScopeId", PageContextValue.of(null)));
            }
        };
        PageSelectionContextRequest request = request("platform");

        ResolvedPageSelectionContext resolved = new PageSelectionContextResolverRegistry(List.of(resolver)).resolve(request);

        assertThat(resolved.values().get("ownerScopeId").present()).isTrue();
        assertThat(resolved.values().get("ownerScopeId").value()).isNull();
    }

    @Test
    void failsClosedForAnUnknownOrMismatchedResolverResult() {
        PageSelectionContextResolver mismatched = new PageSelectionContextResolver() {
            @Override public String selectionKind() { return "roleScope"; }
            @Override public ResolvedPageSelectionContext resolve(PageSelectionContextRequest request) {
                return new ResolvedPageSelectionContext("roleScope", "another", Map.of());
            }
        };

        assertThatIllegalArgumentException().isThrownBy(() ->
                new PageSelectionContextResolverRegistry(List.of()).resolve(request("platform")));
        assertThatIllegalStateException().isThrownBy(() ->
                new PageSelectionContextResolverRegistry(List.of(mismatched)).resolve(request("platform")));
    }

    @Test
    void rejectsDuplicateResolverKinds() {
        PageSelectionContextResolver resolver = new PageSelectionContextResolver() {
            @Override public String selectionKind() { return "roleScope"; }
            @Override public ResolvedPageSelectionContext resolve(PageSelectionContextRequest request) {
                return new ResolvedPageSelectionContext("roleScope", request.selectionKey(), Map.of());
            }
        };

        assertThatIllegalArgumentException().isThrownBy(() ->
                new PageSelectionContextResolverRegistry(List.of(resolver, resolver)));
    }

    private static PageSelectionContextRequest request(String key) {
        return new PageSelectionContextRequest("iam.role", "roleScope", key, PlatformAction.QUERY,
                CurrentUser.systemUser("admin", "Admin"), "menu-id");
    }
}
