package net.ximatai.muyun.spring.ability.permission;

import java.util.List;

/** A business operation, never a patch of internal authorization columns. */
public record RecordPermissionChange(Integer version, Operation operation, Relation relation,
        List<String> userIds, String previousUserId, Boolean retainPreviousOwner) {
    public enum Operation { TRANSFER, ADD, REPLACE, REMOVE }
    public enum Relation { ASSIGNEE, MEMBER }
    public RecordPermissionChange {
        userIds = userIds == null ? List.of() : List.copyOf(userIds);
    }
}
