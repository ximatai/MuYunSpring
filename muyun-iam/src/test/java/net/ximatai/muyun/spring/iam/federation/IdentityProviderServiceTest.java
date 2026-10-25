package net.ximatai.muyun.spring.iam.federation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

class IdentityProviderServiceTest {
    private final ExternalIdentityDao identityDao = mock(ExternalIdentityDao.class);
    private final IdentityProviderService service = new IdentityProviderService(mock(IdentityProviderDao.class),
            List.of(new StrictProtocol()), List.of(), identityDao, name -> "SECRET_VAR".equals(name) ? "s3cr3t" : null, mock(ExternalIdentityAdmissionLocks.class));

    @Test
    void directoryOnlyProtocolsAlsoLockTheirAuthorityConfigurationOnceBound() {
        ExternalDirectoryProtocol directory =
                new ExternalDirectoryProtocol() {
                    public String providerType() {
                        return "directory-only";
                    }

                    public Set<String> identityScopeConfigurationKeys() {
                        return Set.of("issuer");
                    }

                    public Department department(IdentityProviderDescriptor p, String id) {
                        throw new UnsupportedOperationException();
                    }

                    public Page<Department> departments(
                            IdentityProviderDescriptor p, String id, String cursor, int limit) {
                        throw new UnsupportedOperationException();
                    }

                    public Page<String> departmentPeople(
                            IdentityProviderDescriptor p, String id, String cursor, int limit) {
                        throw new UnsupportedOperationException();
                    }

                    public Person person(IdentityProviderDescriptor p, String id) {
                        throw new UnsupportedOperationException();
                    }
                };
        var service = new IdentityProviderService(mock(IdentityProviderDao.class),List.of(),List.of(directory), identityDao, mock(ExternalIdentityAdmissionLocks.class));
        when(identityDao.query(any(Criteria.class),any(PageRequest.class))).thenReturn(List.of(new ExternalIdentity()));
        var old = provider("directory"); old.setProviderType("directory-only"); old.setConfiguration("{\"issuer\":\"old\"}");
        var changed = provider("directory");
        changed.setProviderType("directory-only");
        changed.setConfiguration("{\"issuer\":\"new\"}");
        try (var ignored = systemUser()) {
            assertThatThrownBy(() -> service.beforeUpdate(changed,old)).isInstanceOf(PlatformException.class);
        }
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    @Test
    void onlyPlatformSuperAdministratorsMaintainProviders() {
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("u1", "alice", "tenant-a"))) {
            assertThatThrownBy(() -> service.beforePrepareInsert(provider("corp_idp")))
                    .isInstanceOf(PlatformAccessDeniedException.class);
        }
    }

    @Test
    void rejectsCodesTooLongForTheSessionLoginMethod() {
        try (var ignored = systemUser()) {
            assertThatThrownBy(() -> service.beforePrepareInsert(provider("p" + "x".repeat(48))))
                    .isInstanceOf(PlatformException.class);
            IdentityProvider fits = provider("p" + "x".repeat(47));
            service.beforePrepareInsert(fits);
            assertThat(fits.getId()).hasSize(48);
        }
    }

    @Test
    void validatesProtocolTypeSecretVariableAndProtocolConfiguration() {
        try (var ignored = systemUser()) {
            IdentityProvider unknownType = provider("corp_idp");
            unknownType.setProviderType("missing");
            assertThatThrownBy(() -> service.beforeInsert(unknownType)).isInstanceOf(PlatformException.class);

            IdentityProvider badVariable = provider("corp_idp");
            badVariable.setClientSecretEnvironmentVariable("not a variable");
            assertThatThrownBy(() -> service.beforeInsert(badVariable)).isInstanceOf(PlatformException.class);

            IdentityProvider notJson = provider("corp_idp");
            notJson.setConfiguration("not json");
            assertThatThrownBy(() -> service.beforeInsert(notJson)).isInstanceOf(PlatformException.class);

            IdentityProvider missingEndpoint = provider("corp_idp");
            missingEndpoint.setConfiguration("{\"scope\":\"login\"}");
            assertThatThrownBy(() -> service.beforeInsert(missingEndpoint))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("endpoint");
        }
    }

    @Test
    void locksIdentityScopeOnceBindingsExist() {
        IdentityProvider existing = provider("corp_idp");
        existing.setExternalEnterpriseId("corp-a");
        IdentityProvider changed = provider("corp_idp");
        changed.setExternalEnterpriseId("corp-b");
        try (var ignored = systemUser()) {
            when(identityDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
            service.beforeUpdate(changed, existing);

            when(identityDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(new ExternalIdentity()));
            assertThatThrownBy(() -> service.beforeUpdate(changed, existing)).isInstanceOf(PlatformException.class);
            IdentityProvider renamed = provider("corp_idp");
            renamed.setExternalEnterpriseId("corp-a");
            renamed.setTitle("Renamed");
            service.beforeUpdate(renamed, existing);
        }
    }

    @Test
    void locksTheIssuingAuthorityButNotOrdinarySettingsOnceBindingsExist() {
        IdentityProvider existing = provider("corp_idp");
        existing.setClientId("app-a");
        existing.setConfiguration("{\"endpoint\":\"https://idp.test\",\"issuer\":\"https://issuer-a\"}");
        try (var ignored = systemUser()) {
            when(identityDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(new ExternalIdentity()));
            IdentityProvider otherApplication = provider("corp_idp");
            otherApplication.setClientId("app-b");
            otherApplication.setConfiguration(existing.getConfiguration());
            assertThatThrownBy(() -> service.beforeUpdate(otherApplication, existing)).isInstanceOf(PlatformException.class);

            IdentityProvider otherIssuer = provider("corp_idp");
            otherIssuer.setClientId("app-a");
            otherIssuer.setConfiguration("{\"endpoint\":\"https://idp.test\",\"issuer\":\"https://issuer-b\"}");
            assertThatThrownBy(() -> service.beforeUpdate(otherIssuer, existing)).isInstanceOf(PlatformException.class);

            IdentityProvider movedEndpoint = provider("corp_idp");
            movedEndpoint.setClientId("app-a");
            movedEndpoint.setConfiguration("{\"endpoint\":\"https://idp-new.test\",\"issuer\":\"https://issuer-a\"}");
            service.beforeUpdate(movedEndpoint, existing);
        }
    }

    @Test
    void handsProtocolsAReadOnlyDescriptorWithTheResolvedSecretKeptOutOfText() {
        IdentityProvider provider = provider("corp_idp");
        provider.setClientSecretEnvironmentVariable("SECRET_VAR");

        IdentityProviderDescriptor descriptor = service.descriptorOf(provider);

        assertThat(descriptor.clientSecret()).isEqualTo("s3cr3t");
        assertThat(descriptor.configuration()).containsEntry("endpoint", "https://idp.test");
        assertThat(descriptor.identityNamespace()).isEqualTo("corp_idp");
        assertThat(descriptor.toString()).doesNotContain("s3cr3t");
    }

    private static CurrentUserContext.Scope systemUser() {
        return CurrentUserContext.use(CurrentUser.systemUser("admin", "admin"));
    }

    private static IdentityProvider provider(String id) {
        IdentityProvider provider = new IdentityProvider();
        provider.setId(id);
        provider.setTitle("Corp");
        provider.setProviderType(StrictProtocol.TYPE);
        provider.setConfiguration("{\"endpoint\":\"https://idp.test\"}");
        return provider;
    }

    private static final class StrictProtocol implements ExternalLoginProtocol {
        static final String TYPE = "strict";

        @Override
        public String providerType() {
            return TYPE;
        }

        @Override
        public Set<String> identityScopeConfigurationKeys() {
            return Set.of("issuer");
        }

        @Override
        public void validateConfiguration(Map<String, String> configuration) {
            if (!configuration.containsKey("endpoint")) {
                throw new IllegalArgumentException("endpoint is required");
            }
        }

        @Override
        public URI authorizationUri(IdentityProviderDescriptor provider, ExternalAuthorizationContext context) {
            return URI.create("https://idp.test/authorize");
        }

        @Override
        public VerifiedExternalIdentity verify(IdentityProviderDescriptor provider, Map<String, String> callbackParameters,
                                               ExternalAuthorizationContext context) {
            throw ExternalIdentityVerificationException.rejected("not used");
        }
    }
}
