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
}
