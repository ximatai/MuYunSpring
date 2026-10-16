package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.List;

/** Native metadata labels are independent of explicit custom labels authored in individual pages. */
@Service
public class MetadataConfiguredTextSourceProvider implements ConfiguredTextSourceProvider {
    private final ObjectProvider<DynamicRecordService> records;
    private final ObjectProvider<PlatformModuleService> modules;
    public MetadataConfiguredTextSourceProvider(ObjectProvider<DynamicRecordService> records, ObjectProvider<PlatformModuleService> modules) {
        this.records = records; this.modules = modules;
    }
    @Override public List<TextReference> currentSources(String moduleAlias) {
        var moduleService = modules.getIfAvailable();
        var recordService = records.getIfAvailable();
        if (moduleService == null || recordService == null) return List.of();
        var module = moduleService.resolveVisibleModule(moduleAlias);
        if (module == null || module.getModuleKind() != ModuleKind.DYNAMIC) return List.of();
        var descriptor = recordService.describe(moduleAlias);
        return descriptor.entities().stream().flatMap(entity -> entity.fields().stream()
                .filter(field -> field.title() != null && !field.title().isBlank())
                .map(field -> ConfigurationTextSources.metadataFieldTitle(moduleAlias, entity.entityAlias(), field.fieldName(), field.title()))).toList();
    }
}
