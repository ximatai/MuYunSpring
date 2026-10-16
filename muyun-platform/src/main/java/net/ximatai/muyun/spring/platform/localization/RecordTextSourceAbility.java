package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.Map;

/** Explicit configuration labels on a record; ordinary business values have no sources by default. */
public interface RecordTextSourceAbility<T extends EntityContract> extends CrudAbility<T> {
    Map<String, TextReference> recordTextSources(T record);
}
