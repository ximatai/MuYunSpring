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
}
