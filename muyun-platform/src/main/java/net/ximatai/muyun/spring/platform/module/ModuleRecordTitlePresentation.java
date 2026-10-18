package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.content.BusinessContentTextPresentation;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

/** Optional output adapter for a title from the same authorized row, after LIST field protection. */
public interface ModuleRecordTitlePresentation {
    BusinessContentTextPresentation project(CrudAbility<?> owner, EntityContract record, String field, String exposedText);
}
