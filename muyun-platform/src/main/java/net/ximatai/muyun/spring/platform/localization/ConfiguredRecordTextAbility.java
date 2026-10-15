package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import net.ximatai.muyun.spring.common.model.capability.TitledCapable;

/** Configuration producers whose record labels share their module's translation-maintenance scope. */
public interface ConfiguredRecordTextAbility<T extends EntityContract & TitledCapable> extends RecordTextSourceAbility<T>, ConfiguredTextSourceProvider {
    @Override default Map<String, TextReference> recordTextSources(T record) {
        return record.getTitle() == null || record.getTitle().isBlank() ? Map.of() : Map.of("title",
                ConfigurationTextSources.recordText(getModuleAlias(), record.getId(), "title", record.getTitle()));
    }
    @Override default List<TextReference> currentSources(String moduleAlias) {
        if (!TenantContext.isSystem()) throw new IllegalStateException("Configuration source maintenance requires system scope");
        if (!getModuleAlias().equals(moduleAlias)) return List.of();
        return list(Criteria.of(), new PageRequest(0, Integer.MAX_VALUE)).stream()
                .flatMap(record -> recordTextSources(record).values().stream())
                .filter(source -> source.sourceKind() == TextReference.SourceKind.CONFIG).toList();
    }
}
