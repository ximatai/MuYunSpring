package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.model.standard.StandardDataScopedEntity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformPermissionMutationGuardTest {
    static class Record extends StandardDataScopedEntity { }
    @Test void ordinarySparseUpdatesPreserveAllPermissionRelations() {
        Record existing = new Record(); existing.setAuthUserId("owner"); existing.setAuthMemberIds("member");
        Record supplied = new Record();
        PlatformPermissionMutationGuard.validate(supplied, existing);
        assertEquals("owner", supplied.getAuthUserId()); assertEquals("member", supplied.getAuthMemberIds());
    }
    @Test void ordinaryFormsCannotChangePermissionsOrAssignThemAtCreation() {
        Record existing = new Record(); existing.setAuthUserId("owner");
        Record supplied = new Record(); supplied.setAuthUserId("forged");
        assertThrows(RuntimeException.class, () -> PlatformPermissionMutationGuard.validate(supplied, existing));
        assertThrows(RuntimeException.class, () -> PlatformPermissionMutationGuard.validate(supplied, null));
    }
    @Test void rejectionHasSharedEnglishSourceWithoutChangingCodeOrStatus() {
        var catalog = net.ximatai.muyun.spring.common.i18n.LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        var texts = net.ximatai.muyun.spring.common.i18n.TextResources.load(getClass().getClassLoader(), catalog);
        var handler = new PlatformWebExceptionHandler(); handler.setLanguageCatalog(catalog); handler.setTextResolver(texts);
        var failure = (net.ximatai.muyun.spring.ability.action.BusinessException) PlatformPermissionMutationGuard.violation();
        try (var language = net.ximatai.muyun.spring.common.i18n.LanguageContext.use("en")) {
            var response = handler.handleBusinessException(failure);
            assertEquals(400, response.getStatusCode().value());
            assertEquals("VALIDATION_FAILED", response.getBody().code());
            assertEquals("Use the permission action to change access permissions", response.getBody().message());
            assertEquals("请通过授权动作变更权限", response.getBody().messageText().source().originalText());
            assertEquals("platform.validation.permissions.action-required", response.getBody().textSource().sourceId());
        }
    }
}
