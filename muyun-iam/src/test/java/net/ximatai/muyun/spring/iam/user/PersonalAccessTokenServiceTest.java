package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PersonalAccessTokenServiceTest {
    private final Instant now = Instant.parse("2026-10-27T00:00:00Z");
    private final PersonalAccessTokenStore tokens = mock(PersonalAccessTokenStore.class);
    private final UserAccountService accounts = mock(UserAccountService.class);
    private final UserSessionService sessions = mock(UserSessionService.class);
    private final CurrentUser admin = CurrentUser.systemUser("admin", "admin");
    private final net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService permissions = mock(net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService.class);
    private final net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier tenants = mock(net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier.class);
    private final net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher events = mock(net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher.class);
    private final PersonalAccessTokenService service = new PersonalAccessTokenService(tokens, accounts, sessions,
            UserSessionCollaborators.empty(), Clock.fixed(now, ZoneOffset.UTC),
            events, permissions, tenants);
    private UserAccount owner;

    @BeforeEach void setup() {
        when(permissions.authorizeAction(anyString(), any(), any())).thenAnswer(call ->
            net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult.allowed(
                net.ximatai.muyun.spring.common.platform.ActionExecutionContext.ofPolicy(call.getArgument(0), call.getArgument(1), Set.of(), call.getArgument(2))));
        owner = new UserAccount(); owner.setId("admin"); owner.setUsername("admin"); owner.setEnabled(true);
        when(accounts.select("admin")).thenReturn(owner);
        when(sessions.currentUserSnapshot("session")).thenReturn(Optional.of(admin));
        when(accounts.withPersonalAccessTokenOwner(eq(admin), any())).thenAnswer(call -> {
            Function<UserAccount, ?> issue = call.getArgument(1); return issue.apply(owner);
        });
        doAnswer(call -> { PersonalAccessToken token = call.getArgument(0); token.setId("key-1");
            token.setCreatedAt(now); return null; }).when(tokens).issue(any());
    }

    @Test void issuesOnlyAnOwnerBoundHashWithOneTimeSecretAndExplicitExpiry() {
        doAnswer(call -> {
            assertThat(TenantContext.isSystem()).isTrue();
            assertThat(TenantContext.currentTenantId()).isEmpty();
            return Optional.of(admin);
        }).when(sessions).currentUserSnapshot("session");
        IssuedPersonalAccessToken issued;
        try (var user = CurrentUserContext.use(admin); var tenant = TenantContext.use("business-a")) {
            issued = service.create("session", " USB 工具 ", 90);
            assertThat(TenantContext.currentTenantId()).contains("business-a");
        }
        var captor = ArgumentCaptor.forClass(PersonalAccessToken.class); verify(tokens).issue(captor.capture());
        PersonalAccessToken stored = captor.getValue();
        assertThat(issued.token()).matches("muyun_pat_[A-Za-z0-9_-]{43}");
        assertThat(stored.getTokenHash()).hasSize(64).doesNotContain(issued.token());
        assertThat(stored.getTenantId()).isNull();
        assertThat(stored.getUserId()).isEqualTo("admin");
        assertThat(stored.getName()).isEqualTo("USB 工具");
        assertThat(stored.getExpiresAt()).isEqualTo(now.plus(Duration.ofDays(90)));
        assertThat(issued.toString()).doesNotContain(issued.token());
        verify(sessions, times(2)).currentUserSnapshot("session");
    }

    @Test void refusesAnonymousUnavailableAndRestrictedSessions() {
        assertThatThrownBy(() -> service.create("session", "tool", 90)).isInstanceOf(PlatformException.class);
        for (CurrentUser user : List.of(CurrentUser.tenantUser("u", "u", "t"),
                CurrentUser.systemUser("admin", "admin", true))) {
            try (var ignored = CurrentUserContext.use(user)) {
                assertThatThrownBy(() -> service.create("session", "tool", 90)).isInstanceOf(PlatformException.class);
            }
        }
        verify(tokens, never()).issue(any());
    }

    @Test void managementRequiresTheSameActiveInteractiveUserWithoutAnAdditionalLoginWindow() {
        try (var user = CurrentUserContext.use(admin)) {
            when(sessions.currentUserSnapshot("pat")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.create("pat", "tool", 90)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(() -> service.list("pat")).isInstanceOf(PlatformException.class);
            assertThatThrownBy(() -> service.revoke("pat", "key")).isInstanceOf(PlatformException.class);
            assertThatThrownBy(() -> service.delete("pat", "key")).isInstanceOf(PlatformException.class);
            when(sessions.currentUserSnapshot("session")).thenReturn(Optional.of(CurrentUser.systemUser("other", "other")));
            assertThatThrownBy(() -> service.create("session", "tool", 90)).isInstanceOf(PlatformException.class);
            when(sessions.currentUserSnapshot("session")).thenReturn(Optional.of(admin));
            assertThat(service.create("session", "tool", 90).token()).startsWith(PersonalAccessTokenService.PREFIX);
            service.list("session"); service.revoke("session", "key");
            verify(tokens).revoke("admin", "key", now);
        }
    }

    @Test void validatesNameAndLifetimeBeforePersisting() {
        try (var user = CurrentUserContext.use(admin)) {
            for (Integer days : Arrays.asList(null, 0, 366))
                assertCode(() -> service.create("session", "tool", days), "VALIDATION_FAILED");
            for (String name : Arrays.asList(null, " ", "x".repeat(101)))
                assertCode(() -> service.create("session", name, 90), "VALIDATION_FAILED");
        }
        verify(tokens, never()).issue(any());
    }

    @Test void authenticatesTheLiveUserWithoutChangingTheBusinessTenant() {
        var issued = issue();
        when(tokens.findByHash(anyString())).thenReturn(token(issued));
        owner.setUsername("renamed");
        try (var tenant = TenantContext.use("business-b")) {
            var authentication = service.authenticate(issued.token());
            assertThat(authentication.user().username()).isEqualTo("renamed");
            assertThat(authentication.user().system()).isTrue();
            assertThat(authentication.credentialId()).isEqualTo("key-1");
            assertThat(TenantContext.currentTenantId()).contains("business-b");
        }
    }

    @Test void onlyMatchedSecretsRevealExpiryRevocationOrUnavailableOwner() {
        assertCode(() -> service.authenticate("bad"), "ACCESS_TOKEN_INVALID");
        var issued = issue();
        assertCode(() -> service.authenticate(issued.token()), "ACCESS_TOKEN_INVALID");
        var token = token(issued); when(tokens.findByHash(anyString())).thenReturn(token);
        token.setExpiresAt(now);
        assertCode(() -> service.authenticate(issued.token()), "ACCESS_TOKEN_EXPIRED");
        token.setRevokedAt(now);
        assertCode(() -> service.authenticate(issued.token()), "ACCESS_TOKEN_REVOKED");
        token.setRevokedAt(null); token.setExpiresAt(now.plusSeconds(1)); owner.setEnabled(false);
        assertCode(() -> service.authenticate(issued.token()), "ACCESS_TOKEN_OWNER_UNAVAILABLE");
        owner.setEnabled(true); owner.setDeleted(true);
        assertCode(() -> service.authenticate(issued.token()), "ACCESS_TOKEN_OWNER_UNAVAILABLE");
    }

    @Test void grantsCreationToTenantUsersInTheirOwnRealmAndRetainsSelfCleanupAfterPermissionRemoval() {
        var user = CurrentUser.tenantUser("u", "user", "owner-tenant");
        owner.setId("u"); owner.setTenantId("owner-tenant");
        when(accounts.select("u")).thenReturn(owner);
        when(accounts.withPersonalAccessTokenOwner(eq(user), any())).thenAnswer(call -> {
            Function<UserAccount, ?> issue = call.getArgument(1); return issue.apply(owner);
        });
        when(sessions.currentUserSnapshot("session")).thenReturn(Optional.of(user));
        doAnswer(call -> {
            assertThat(TenantContext.currentTenantId()).contains("owner-tenant");
            assertThat(net.ximatai.muyun.spring.common.identity.ActingContextHolder.current()).isEmpty();
            assertThat(call.getArgument(1, net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy.class))
                    .isEqualTo(PersonalAccessTokenService.CREATE_POLICY);
            return new net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult("ROLE_GRANTED", "u", "USER", "iam.personal_access_token.create", "create");
        }).when(permissions).authorizeAction(anyString(), any(), any());
        var actingContext = new net.ximatai.muyun.spring.common.identity.ActingContext("delegation", user,
                net.ximatai.muyun.spring.common.identity.BusinessPrincipal.userAccount("principal"), "iam.personal_access_token", "create");
        try (var actor = CurrentUserContext.use(user); var target = TenantContext.use("other-tenant");
             var acting = net.ximatai.muyun.spring.common.identity.ActingContextHolder.use(actingContext)) {
            assertThat(service.canCreate("session")).isTrue();
            var issued = service.create("session", "tenant tool", 90);
            var stored = token(issued); stored.setUserId("u");
            when(tokens.findByHash(anyString())).thenReturn(stored);
            doThrow(new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("denied"))
                    .when(permissions).authorizeAction(anyString(), any(), any());
            assertThat(service.canCreate("session")).isFalse();
            assertThatThrownBy(() -> service.create("session", "another", 90)).isInstanceOf(PlatformException.class);
            assertThat(service.authenticate(issued.token()).user().tenantId()).isEqualTo("owner-tenant");
            service.list("session"); service.revoke("session", "key-1");
            verify(tokens).revoke("u", "key-1", now);
            assertThat(TenantContext.currentTenantId()).contains("other-tenant");
        }
        verify(tenants, atLeastOnce()).verifyActiveTenant("owner-tenant");
    }

    @Test void tenantLifecycleGateAndOwnerIdentityAreCheckedWithoutAddingTenantGrantsToKeys() {
        var issued = issue();
        when(tokens.findByHash(anyString())).thenReturn(token(issued));
        owner.setTenantId("tenant-a");
        assertThat(service.authenticate(issued.token()).user().tenantId()).isEqualTo("tenant-a");
        doThrow(new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException("inactive tenant"))
                .when(tenants).verifyActiveTenant("tenant-a");
        assertCode(() -> service.authenticate(issued.token()), "ACCESS_TOKEN_OWNER_UNAVAILABLE");
        try (var actor = CurrentUserContext.use(admin)) {
            assertThatThrownBy(() -> service.list("session")).isInstanceOf(PlatformException.class);
        }
    }

    @Test void selfRevocationDoesNotClaimDelegatedAuthorityInAudit() {
        when(tokens.revoke("admin", "key", now)).thenReturn(true);
        var context = new net.ximatai.muyun.spring.common.identity.ActingContext("delegation", admin,
                net.ximatai.muyun.spring.common.identity.BusinessPrincipal.userAccount("principal"), "iam.personal_access_token", "revoke");
        try (var actor = CurrentUserContext.use(admin);
             var acting = net.ximatai.muyun.spring.common.identity.ActingContextHolder.use(context)) {
            service.revoke("session", "key");
            assertThat(net.ximatai.muyun.spring.common.identity.ActingContextHolder.current()).contains(context);
        }
        var event = ArgumentCaptor.forClass(net.ximatai.muyun.spring.ability.event.RuntimeEvent.class);
        verify(events).publishAfterCommit(event.capture());
        assertThat(event.getValue().operatorId()).isEqualTo("admin");
        assertThat(event.getValue().moduleAlias()).isEqualTo(PersonalAccessTokenService.MODULE_ALIAS);
        assertThat(event.getValue().authorizationDecision()).isEqualTo("LOGIN_REQUIRED");
        assertThat(event.getValue().auditContext()).doesNotContainKey("actingDelegationId");
    }

    @Test void deletionUsesTheInteractiveOwnerAndEmitsOneAuditFactOnlyForAnActualChange() {
        when(tokens.deleteRevoked("admin", "key", now)).thenReturn(true, false);
        try (var actor = CurrentUserContext.use(admin)) {
            service.delete("session", "key");
            service.delete("session", "key");
        }
        verify(tokens, times(2)).deleteRevoked("admin", "key", now);
        var event = ArgumentCaptor.forClass(net.ximatai.muyun.spring.ability.event.RuntimeEvent.class);
        verify(events).publishAfterCommit(event.capture());
        assertThat(event.getValue().actionCode()).isEqualTo("delete");
        assertThat(event.getValue().recordId()).isEqualTo("key");
        assertThat(event.getValue().operatorId()).isEqualTo("admin");
        assertThat(event.getValue().moduleAlias()).isEqualTo(PersonalAccessTokenService.MODULE_ALIAS);
    }

    private IssuedPersonalAccessToken issue() {
        try (var user = CurrentUserContext.use(admin)) { return service.create("session", "tool", 90); }
    }
    private PersonalAccessToken token(IssuedPersonalAccessToken issued) {
        var token = new PersonalAccessToken(); token.setId("key-1"); token.setUserId("admin");
        token.setExpiresAt(issued.accessToken().expiresAt()); return token;
    }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PlatformException.class, error -> {
            assertThat(error.code()).isEqualTo(code);
            assertThat(error.httpStatus()).isEqualTo(code.equals("VALIDATION_FAILED") ? 400 : 401);
        });
    }
}
