package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.employee.EmployeeDelegation;
import net.ximatai.muyun.spring.iam.employee.EmployeeDelegationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IamTrustedExecutionIdentityResolverTest {
    private static final Instant CREATED = Instant.parse("2026-08-01T00:00:00Z");
    private final Clock clock = Clock.fixed(CREATED.plusSeconds(60), ZoneOffset.UTC);
    private final UserAccountService accounts = mock(UserAccountService.class);
    private final UserAccountDao dao = mock(UserAccountDao.class);
    private final ActiveTenantVerifier tenants = mock(ActiveTenantVerifier.class);
    private final EmployeeDelegationService grants = mock(EmployeeDelegationService.class);
    private final AtomicReference<String> organization = new AtomicReference<>("org-before");
    private final AtomicReference<ZoneId> zone = new AtomicReference<>(ZoneId.of("Asia/Shanghai"));
    private final AtomicReference<UserAccount> persisted = new AtomicReference<>(account("tenant-a"));
    private final List<String> lookupTenants = new ArrayList<>();
    private final IamTrustedExecutionIdentityResolver resolver;

    @SuppressWarnings("unchecked")
    IamTrustedExecutionIdentityResolverTest() {
        when(accounts.getDao()).thenReturn(dao);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenAnswer(invocation -> {
            lookupTenants.add(TenantContext.currentTenantId().orElse("platform"));
            return persisted.get() == null ? List.of() : List.of(persisted.get());
        });
        ObjectProvider<EmployeeDelegationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(grants);
        resolver = new IamTrustedExecutionIdentityResolver(accounts, tenants,
                new UserSessionCollaborators(null, null, null, user -> Optional.of(zone.get()),
                        user -> Optional.of(organization.get()), null), provider, clock);
    }

    @AfterEach void clear() { CurrentUserContext.clear(); ActingContextHolder.clear(); TenantContext.clear(); }

    @Test void resolvesLiveAccountWithoutImpersonatingAmbientPlatformActorOrChangingRoutingScope() {
        var requester = CurrentUser.tenantUser("reader", "Before", "tenant-a", "org-before");
        TrustedExecutionIdentityResolver.Reference reference;
        try (var actor = CurrentUserContext.use(requester); var route = TenantContext.use("business-b")) {
            reference = resolver.capture("quality.issue", "view").orElseThrow();
            assertThat(TenantContext.currentTenantId()).contains("business-b");
            assertThat(CurrentUserContext.currentUser()).contains(requester);
        }
        organization.set("org-now"); zone.set(ZoneId.of("Europe/Berlin"));
        persisted.get().setUsername("Now");
        var ambient = CurrentUser.systemUser("control", "Control");
        var principal = acting("ambient", ambient, "other", "view");
        try (var actor = CurrentUserContext.use(ambient); var route = TenantContext.use("routing-c");
             var acting = ActingContextHolder.use(principal)) {
            var identity = resolver.resolve(reference, "quality.issue", "view").orElseThrow();
            assertThat(identity.user()).isEqualTo(CurrentUser.tenantUser("reader", "Now", "tenant-a", "org-now")
                    .withTimeZone("Europe/Berlin"));
            assertThat(identity.actingContext()).isNull();
            assertThat(CurrentUserContext.currentUser()).contains(ambient);
            assertThat(ActingContextHolder.current()).contains(principal);
            assertThat(TenantContext.currentTenantId()).contains("routing-c");
        }
        assertThat(lookupTenants).containsExactly("tenant-a", "tenant-a");
        verify(tenants, times(2)).verifyActiveTenant("tenant-a");
    }

    @Test void rejectsWithdrawnOrReusedAccountsAndCurrentPasswordRestrictions() {
        var reference = new TrustedExecutionIdentityResolver.Reference("reader", "tenant-a", CREATED, null);
        var row = persisted.get();
        row.setEnabled(false); assertThat(resolve(reference)).isEmpty(); row.setEnabled(true);
        row.setDeleted(true); assertThat(resolve(reference)).isEmpty(); row.setDeleted(false);
        row.setTenantId("other"); assertThat(resolve(reference)).isEmpty(); row.setTenantId("tenant-a");
        row.setCreatedAt(CREATED.plusSeconds(1)); assertThat(resolve(reference)).isEmpty(); row.setCreatedAt(CREATED);
        when(accounts.passwordChangeRequired(row, clock.instant())).thenReturn(true);
        assertThat(resolve(reference)).isEmpty();
        when(accounts.passwordChangeRequired(row, clock.instant())).thenReturn(false);
        persisted.set(null); assertThat(resolve(reference)).isEmpty();
        try (var actor = CurrentUserContext.use(CurrentUser.tenantUser("reader", "Reader", "tenant-a", null, true))) {
            assertThat(resolver.capture("quality.issue", "view")).isEmpty();
        }
    }

    @Test void keepsRealPlatformAccountIdentityDistinctFromSelectedBusinessTenant() {
        persisted.set(account(null));
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("reader", "Reader"));
             var route = TenantContext.use("business-b")) {
            var reference = resolver.capture("quality.issue", "view").orElseThrow();
            assertThat(reference.accountTenantId()).isNull();
            assertThat(resolver.resolve(reference, "quality.issue", "view").orElseThrow().user().system()).isTrue();
            assertThat(TenantContext.currentTenantId()).contains("business-b");
        }
        assertThat(lookupTenants).containsExactly("platform", "platform");
        verifyNoInteractions(tenants);
    }

    @Test void restoresOnlyTheExactLiveDelegationUsingTheRealOperator() {
        var user = CurrentUser.tenantUser("reader", "Reader", "tenant-a");
        var grant = grant(); var initial = acting("grant", user, "quality.issue", "view");
        when(grants.selectActiveRaw("grant")).thenReturn(grant);
        when(grants.resolveActingContext(any(), anyString(), isNull(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    assertThat(ActingContextHolder.current()).isEmpty();
                    assertThat(CurrentUserContext.currentUser().orElseThrow().userId()).isEqualTo("reader");
                    return acting("grant", invocation.getArgument(0), "quality.issue", "view");
                });
        TrustedExecutionIdentityResolver.Reference reference;
        try (var actor = CurrentUserContext.use(user); var scope = ActingContextHolder.use(initial)) {
            reference = resolver.capture("quality.issue", "view").orElseThrow();
            assertThat(resolver.capture("quality.issue", "update")).isEmpty();
            assertThat(ActingContextHolder.current()).contains(initial);
        }
        assertThat(resolve(reference).orElseThrow().actingContext().delegationId()).isEqualTo("grant");
        grant.setPrincipalEmployeeId("different"); assertThat(resolve(reference)).isEmpty(); grant.setPrincipalEmployeeId("principal");
        grant.setCreatedAt(CREATED.plusSeconds(1)); assertThat(resolve(reference)).isEmpty(); grant.setCreatedAt(CREATED);
        grant.setEnabled(false); assertThat(resolve(reference)).isEmpty(); grant.setEnabled(true);
        doReturn(acting("replacement-grant", user, "quality.issue", "view")).when(grants)
                .resolveActingContext(any(), anyString(), isNull(), anyString(), anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> resolve(reference)).hasMessageContaining("original delegation");
    }

    @Test void restoresAmbientContextsWhenTenantValidationFails() {
        doThrow(new IllegalStateException("inactive tenant")).when(tenants).verifyActiveTenant("tenant-a");
        var ambient = CurrentUser.systemUser("control", "Control");
        try (var actor = CurrentUserContext.use(ambient); var route = TenantContext.use("routing-b")) {
            assertThatThrownBy(() -> resolve(new TrustedExecutionIdentityResolver.Reference("reader", "tenant-a", CREATED, null)))
                    .hasMessage("inactive tenant");
            assertThat(CurrentUserContext.currentUser()).contains(ambient);
            assertThat(TenantContext.currentTenantId()).contains("routing-b");
        }
    }

    @Test void keepsSelectedPositionsSeparateFromTheOriginalGrantsUnboundPositions() {
        var user = CurrentUser.tenantUser("reader", "Reader", "tenant-a");
        var grant = grant(); grant.setPrincipalPositionId(null);
        var selected = new ActingContext("grant", user,
                BusinessPrincipal.employeePosition("principal", "org", "dept", "selected-principal"),
                "quality.issue", "view", "selected-delegate");
        when(grants.selectActiveRaw("grant")).thenReturn(grant);
        when(grants.resolveActingContext(any(), eq("delegate"), eq("selected-delegate"), eq("principal"),
                eq("selected-principal"), eq("quality.issue"), eq("view"), any())).thenAnswer(invocation ->
                new ActingContext("grant", invocation.getArgument(0), selected.principal(),
                        "quality.issue", "view", "selected-delegate"));
        TrustedExecutionIdentityResolver.Reference reference;
        try (var actor = CurrentUserContext.use(user); var context = ActingContextHolder.use(selected)) {
            reference = resolver.capture("quality.issue", "view").orElseThrow();
        }
        assertThat(reference.delegation().principalPositionId()).isNull();
        assertThat(reference.delegation().delegatePositionId()).isNull();
        assertThat(reference.delegation().selectedPrincipalPositionId()).isEqualTo("selected-principal");
        assertThat(reference.delegation().selectedDelegatePositionId()).isEqualTo("selected-delegate");
        assertThat(resolve(reference).orElseThrow().actingContext().delegatePositionId()).isEqualTo("selected-delegate");
        verify(grants, times(2)).resolveActingContext(any(), eq("delegate"), eq("selected-delegate"), eq("principal"),
                eq("selected-principal"), eq("quality.issue"), eq("view"), any());
        grant.setDelegatePositionId("new-bound-position");
        assertThat(resolve(reference)).isEmpty();
    }

    private Optional<TrustedExecutionIdentityResolver.Identity> resolve(TrustedExecutionIdentityResolver.Reference ref) {
        return resolver.resolve(ref, "quality.issue", "view");
    }
    private static UserAccount account(String tenant) {
        var row = new UserAccount(); row.setId("reader"); row.setUsername("Before"); row.setTenantId(tenant);
        row.setCreatedAt(CREATED); row.setEnabled(true); row.setDeleted(false); return row;
    }
    private static EmployeeDelegation grant() {
        var row = new EmployeeDelegation(); row.setId("grant"); row.setTenantId("tenant-a"); row.setCreatedAt(CREATED);
        row.setPrincipalEmployeeId("principal"); row.setPrincipalPositionId("position"); row.setDelegateEmployeeId("delegate");
        return row;
    }
    private static ActingContext acting(String id, CurrentUser operator, String module, String action) {
        return new ActingContext(id, operator, BusinessPrincipal.employeePosition("principal", "org", "dept", "position"), module, action);
    }
}
