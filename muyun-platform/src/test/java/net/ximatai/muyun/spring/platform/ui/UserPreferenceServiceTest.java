package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserPreferenceServiceTest {
    private final UserPreferenceService service = new UserPreferenceService(new TestMemoryDao<>());

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    @Test
    void shouldOverwriteCurrentUsersOpaquePreference() {
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            UserPreference saved = service.saveCurrentUserPreference(PlatformUiClientType.WEB,
                    "workbench.menu-display-depth", "{\"depth\":1}");
            UserPreference updated = service.saveCurrentUserPreference(PlatformUiClientType.WEB,
                    "workbench.menu-display-depth", "{\"depth\":3}");

            assertThat(updated.getId()).isEqualTo(saved.getId());
            assertThat(service.currentUserPreference(PlatformUiClientType.WEB, "workbench.menu-display-depth")
                    .getValueJson()).contains("3");
            service.deleteCurrentUserPreference(PlatformUiClientType.WEB, "workbench.menu-display-depth");
            assertThat(service.currentUserPreference(PlatformUiClientType.WEB, "workbench.menu-display-depth"))
                    .isNull();
        }

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-2", "User", "tenant-a"))) {
            assertThat(service.currentUserPreference(PlatformUiClientType.WEB, "workbench.menu-display-depth"))
                    .isNull();
        }
    }

    @Test
    void shouldRejectInvalidClientOwnedJson() {
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            assertThatThrownBy(() -> service.saveCurrentUserPreference(PlatformUiClientType.WEB,
                    "workbench.menu-display-depth", "not json"))
                    .hasMessage("user preference valueJson must be valid JSON");
        }
    }

    @Test
    void shouldRejectPreferenceKeysLongerThanTheStorageContract() {
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            assertThatThrownBy(() -> service.saveCurrentUserPreference(PlatformUiClientType.WEB,
                    "a".repeat(129), "{}"))
                    .hasMessage("user preference preferenceKey must not exceed 128 characters");
        }
    }

    @Test
    void shouldEnforceTheUtf8PreferenceValueSizeContract() {
        String maximumValue = "\"" + "a".repeat(UserPreferenceService.MAX_VALUE_JSON_BYTES - 2) + "\"";
        String oversizedMultibyteValue = "\""
                + "界".repeat(UserPreferenceService.MAX_VALUE_JSON_BYTES / 3)
                + "\"";
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            assertThat(service.saveCurrentUserPreference(
                    PlatformUiClientType.WEB, "workbench.maximum-value", maximumValue).getValueJson())
                    .isEqualTo(maximumValue);

            assertThatThrownBy(() -> service.saveCurrentUserPreference(
                    PlatformUiClientType.WEB, "workbench.oversized-value", oversizedMultibyteValue))
                    .hasMessage("user preference valueJson must not exceed 65536 UTF-8 bytes");
        }
    }

    @Test
    void shouldSeparateTheSameUserIdAcrossTenantAndSystemScopes() {
        UserPreference tenantPreference;
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("shared-user", "User", "__SYSTEM__"))) {
            tenantPreference = service.saveCurrentUserPreference(
                    PlatformUiClientType.WEB, "workbench.menu-display-depth", "1");
        }

        UserPreference otherTenantPreference;
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("shared-user", "User", "tenant-b"))) {
            assertThat(service.currentUserPreference(PlatformUiClientType.WEB, "workbench.menu-display-depth"))
                    .isNull();
            otherTenantPreference = service.saveCurrentUserPreference(
                    PlatformUiClientType.WEB, "workbench.menu-display-depth", "2");
        }

        UserPreference systemPreference;
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.systemUser("shared-user", "System User"))) {
            assertThat(service.currentUserPreference(PlatformUiClientType.WEB, "workbench.menu-display-depth"))
                    .isNull();
            systemPreference = service.saveCurrentUserPreference(
                    PlatformUiClientType.WEB, "workbench.menu-display-depth", "3");
        }

        assertThat(tenantPreference.getId())
                .isNotEqualTo(otherTenantPreference.getId())
                .isNotEqualTo(systemPreference.getId());
    }

    @Test
    void shouldRejectTenantUserWithoutTenantIdentity() {
        CurrentUser invalidUser = new CurrentUser("user-1", "User", null, null, false, false, null);
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(invalidUser)) {
            assertThatThrownBy(() -> service.currentUserPreference(
                    PlatformUiClientType.WEB, "workbench.menu-display-depth"))
                    .hasMessage("user preference requires tenant id for tenant user");
        }
    }
}
