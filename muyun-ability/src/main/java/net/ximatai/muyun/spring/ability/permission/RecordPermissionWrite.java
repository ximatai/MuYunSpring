package net.ximatai.muyun.spring.ability.permission;

import net.ximatai.muyun.spring.common.model.capability.DataScopeCapable;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

/**
 * The complete, persisted authorization relation state for one record.
 *
 * <p>This deliberately carries no business fields, so a dedicated permission write cannot
 * become a second general-purpose record update path.</p>
 */
public record RecordPermissionWrite(String id,
                                    Integer version,
                                    String ownerId,
                                    String assigneeIds,
                                    String memberIds) {
    public static RecordPermissionWrite from(EntityContract record) {
        if (!(record instanceof DataScopeCapable scoped)) {
            throw new IllegalArgumentException("记录不支持数据权限管理");
        }
        return new RecordPermissionWrite(record.getId(), record.getVersion(), scoped.getAuthUserId(),
                scoped.getAuthAssigneeIds(), scoped.getAuthMemberIds());
    }
}
