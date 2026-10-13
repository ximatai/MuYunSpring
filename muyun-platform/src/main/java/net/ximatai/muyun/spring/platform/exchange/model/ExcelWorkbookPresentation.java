package net.ximatai.muyun.spring.platform.exchange.model;

import net.ximatai.muyun.spring.common.i18n.TextReference;

import net.ximatai.muyun.spring.common.i18n.TextResolver;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.platform.localization.ConfigurationTextSources;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only human column labels are projected; technical headers, sheets, dropdown values and rows stay authoritative. */
@Service
public class ExcelWorkbookPresentation {
    private final ObjectProvider<TextResolver> resolvers;
    public ExcelWorkbookPresentation(ObjectProvider<TextResolver> resolvers) { this.resolvers = resolvers; }
    public ExcelWorkbookPlan project(ExcelWorkbookPlan plan, DynamicModuleDescriptor descriptor) {
        var resolver = resolvers.getIfAvailable();
        if (resolver == null) return plan;
        Map<String, TextReference> sources = new LinkedHashMap<>();
        for (var entity : descriptor.entities()) for (var field : entity.fields())
            if (field.title() != null && !field.title().isBlank()) sources.put(entity.entityAlias() + "/" + field.fieldName(),
                    ConfigurationTextSources.metadataFieldTitle(descriptor.moduleAlias(), entity.entityAlias(), field.fieldName(), field.title()));
        var prepared = resolver.prepare(sources.values());
        return new ExcelWorkbookPlan(plan.meta(), plan.sheets().stream().map(sheet -> new ExcelSheetPlan(sheet.sheetName(), sheet.entityAlias(), sheet.main(),
                sheet.columns().stream().map(column -> {
                    var source = sources.get(sheet.entityAlias() + "/" + column.fieldName());
                    if (source == null) return column;
                    return new ExcelColumnPlan(column.fieldName(), prepared.resolve(source, Map.of()).text(), column.required(), column.valueType(), column.dropdownOptions());
                }).toList(), sheet.rows())).toList());
    }
}
