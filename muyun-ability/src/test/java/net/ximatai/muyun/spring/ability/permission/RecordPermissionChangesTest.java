package net.ximatai.muyun.spring.ability.permission;

import net.ximatai.muyun.spring.common.model.standard.StandardDataScopedEntity;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static net.ximatai.muyun.spring.ability.permission.RecordPermissionChange.Operation.*;
import static net.ximatai.muyun.spring.ability.permission.RecordPermissionChange.Relation.*;

class RecordPermissionChangesTest {
    private StandardDataScopedEntity record() {
        var record = new StandardDataScopedEntity() { }; record.setVersion(3); record.setAuthUserId("owner");
        record.setAuthAssigneeIds("a,b"); record.setAuthMemberIds("c"); record.setAuthDepartmentId("department");
        return record;
    }
    @Test void transferPreservesOrganizationAndOtherRelations() {
        var record = record();
        RecordPermissionChanges.apply(record, new RecordPermissionChange(3, TRANSFER, null, List.of("next"), null, true));
        assertEquals("next", record.getAuthUserId()); assertEquals("c,owner", record.getAuthMemberIds());
        assertEquals("department", record.getAuthDepartmentId()); assertEquals("a,b", record.getAuthAssigneeIds());
    }
    @Test void addIsSetUnionAndReplaceOnlyChangesSelectedMember() {
        var record = record();
        RecordPermissionChanges.apply(record, new RecordPermissionChange(3, ADD, ASSIGNEE, List.of("b","d"), null, null));
        assertEquals("a,b,d", record.getAuthAssigneeIds());
        RecordPermissionChanges.apply(record, new RecordPermissionChange(3, REPLACE, ASSIGNEE, List.of("e"), "b", null));
        assertEquals("a,d,e", record.getAuthAssigneeIds()); assertEquals("c", record.getAuthMemberIds());
    }
    @Test void removeDoesNotChangeOwnership() {
        var record = record();
        RecordPermissionChanges.apply(record, new RecordPermissionChange(3, REMOVE, MEMBER, List.of("c"), null, null));
        assertEquals("", record.getAuthMemberIds()); assertEquals("owner", record.getAuthUserId());
    }
    @Test void rejectsStaleVersionAndMissingReplacement() {
        var record = record();
        assertThrows(OptimisticLockException.class, () -> RecordPermissionChanges.apply(record, new RecordPermissionChange(2, ADD, MEMBER, List.of("x"), null, null)));
        assertThrows(IllegalArgumentException.class, () -> RecordPermissionChanges.apply(record, new RecordPermissionChange(3, REPLACE, MEMBER, List.of("x"), "missing", null)));
        assertEquals("c", record.getAuthMemberIds());
    }
    @Test void rejectsCsvInjectionAndImplicitTransferRetention() {
        var record = record();
        assertThrows(IllegalArgumentException.class, () -> RecordPermissionChanges.apply(record, new RecordPermissionChange(3, ADD, MEMBER, List.of("x,y"), null, null)));
        assertThrows(IllegalArgumentException.class, () -> RecordPermissionChanges.apply(record, new RecordPermissionChange(3, TRANSFER, null, List.of("next"), null, null)));
    }
}
