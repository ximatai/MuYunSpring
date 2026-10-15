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
        var declaredSources = new java.util.ArrayList<>(sources.values());
        plan.sheets().forEach(sheet -> sheet.columns().forEach(column -> {
            if (column.titleSource() != null) declaredSources.add(column.titleSource());
        }));
        var prepared = resolver.prepare(declaredSources);
        return new ExcelWorkbookPlan(plan.meta(), plan.sheets().stream().map(sheet -> new ExcelSheetPlan(sheet.sheetName(), sheet.entityAlias(), sheet.main(),
                sheet.columns().stream().map(column -> {
                    var source = column.titleSource() != null ? column.titleSource()
                            : sources.get(sheet.entityAlias() + "/" + column.fieldName());
                    if (source == null || (column.titleSource() == null && !source.originalText().equals(column.title()))) return column;
                    return new ExcelColumnPlan(column.fieldName(), prepared.resolve(source, Map.of()).text(), column.required(), column.valueType(), column.dropdownOptions(), source);
                }).toList(), sheet.rows())).toList());
    }
}
