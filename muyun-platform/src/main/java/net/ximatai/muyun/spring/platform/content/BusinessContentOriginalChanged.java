package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

/** In-transaction source fact; adapters may establish an authorized deferred reading scope. */
public record BusinessContentOriginalChanged(CrudAbility<?> owner, EntityContract record, String field,
                                             String originalRevisionId) { }
