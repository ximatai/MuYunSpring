package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;

/** Global record visibility; deletion policy and mutation guards remain independent capabilities. */
public interface GlobalScopedAbility<T extends EntityContract> extends CrudAbility<T> {
}
