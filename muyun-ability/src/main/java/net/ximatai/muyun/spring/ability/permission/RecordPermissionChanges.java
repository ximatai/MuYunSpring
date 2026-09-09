package net.ximatai.muyun.spring.ability.permission;

import net.ximatai.muyun.spring.common.model.capability.DataScopeCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Shared static/dynamic relation semantics; persistence and authorization stay with the caller. */
public final class RecordPermissionChanges {
    private RecordPermissionChanges() { }
    public static List<String> ids(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }
    public static void apply(EntityContract record, RecordPermissionChange change) {
        if (!(record instanceof DataScopeCapable target)) throw new IllegalArgumentException("记录不支持数据权限管理");
        if (change == null || change.operation() == null) throw new IllegalArgumentException("请选择权限操作");
        if (change.version() == null || !change.version().equals(record.getVersion()))
            throw new OptimisticLockException("记录已变更，请重新打开权限管理");
        LinkedHashSet<String> selected = new LinkedHashSet<>(change.userIds());
        if (selected.isEmpty() || selected.stream().anyMatch(id -> id == null || id.isBlank() || id.contains(",") || !id.equals(id.trim())))
            throw new IllegalArgumentException("请选择有效人员");
        if (change.operation() == RecordPermissionChange.Operation.TRANSFER) {
            if (selected.size() != 1 || change.retainPreviousOwner() == null)
                throw new IllegalArgumentException("请选择新归属人和原归属人的保留方式");
            String next = selected.iterator().next();
            if (Objects.equals(next, target.getAuthUserId())) throw new IllegalArgumentException("新旧归属人相同");
            if (change.retainPreviousOwner() && target.getAuthUserId() != null) {
                LinkedHashSet<String> members = new LinkedHashSet<>(ids(target.getAuthMemberIds()));
                members.add(target.getAuthUserId());
                target.setAuthMemberIds(String.join(",", members));
            }
            target.setAuthUserId(next);
            return;
        }
        if (change.relation() == null) throw new IllegalArgumentException("请选择负责人或相关人");
        LinkedHashSet<String> members = new LinkedHashSet<>(ids(change.relation() == RecordPermissionChange.Relation.ASSIGNEE
                ? target.getAuthAssigneeIds() : target.getAuthMemberIds()));
        switch (change.operation()) {
            case ADD -> members.addAll(selected);
            case REMOVE -> {
                if (!members.containsAll(selected)) throw new IllegalArgumentException("待移除人员已不在当前名单中");
                members.removeAll(selected);
            }
            case REPLACE -> {
                if (selected.size() != 1 || !members.contains(change.previousUserId()))
                    throw new IllegalArgumentException("请选择一名现有人员及其替换人员");
                if (selected.contains(change.previousUserId())) throw new IllegalArgumentException("新旧人员相同");
                members.remove(change.previousUserId());
                members.addAll(selected);
            }
            default -> throw new IllegalArgumentException("不支持的权限操作");
        }
        if (change.relation() == RecordPermissionChange.Relation.ASSIGNEE) target.setAuthAssigneeIds(String.join(",", members));
        else target.setAuthMemberIds(String.join(",", members));
    }
}
