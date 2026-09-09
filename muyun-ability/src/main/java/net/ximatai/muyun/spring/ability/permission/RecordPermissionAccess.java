package net.ximatai.muyun.spring.ability.permission;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;

/** A record read through MANAGE_PERMISSIONS and the tenant scope that made it visible. */
public record RecordPermissionAccess<T extends EntityContract>(T record, boolean crossTenant) { }
