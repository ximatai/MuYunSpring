package net.ximatai.muyun.spring.ability.permission;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;

/** Preserves a source runtime's mutation lifecycle for the dedicated permission action. */
public interface RecordPermissionPersistence<T extends EntityContract> {
    boolean supportsRecordPermissions();

    /** Reads through the dedicated MANAGE_PERMISSIONS action, without inheriting VIEW scope. */
    RecordPermissionAccess<T> readForPermissionAction(String id);

    int updatePermissions(RecordPermissionWrite write);
}
