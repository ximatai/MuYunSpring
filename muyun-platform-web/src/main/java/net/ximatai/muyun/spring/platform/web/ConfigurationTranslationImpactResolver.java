package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.LinkedHashSet;
import java.util.List;

/** Shared dictionaries invalidate the installed modules that actually consume their declared binding. */
@Component
public class ConfigurationTranslationImpactResolver {
    private final ObjectProvider<DictionaryItemService> dictionaries;
    private final ObjectProvider<ModuleExecutionPlanCatalog> plans;
    public ConfigurationTranslationImpactResolver(ObjectProvider<DictionaryItemService> dictionaries, ObjectProvider<ModuleExecutionPlanCatalog> plans) {
        this.dictionaries = dictionaries; this.plans = plans;
    }
    public List<String> modules(RuntimeEvent event) {
        var result = new LinkedHashSet<String>(); result.add(event.moduleAlias());
        Object identity = event.payload().get("sourceId");
        String prefix = "config/dictionary/item/", suffix = "/title";
        if (!DictionaryItemService.MODULE_ALIAS.equals(event.moduleAlias()) || !(identity instanceof String sourceId) ||
                !sourceId.startsWith(prefix) || !sourceId.endsWith(suffix)) return List.copyOf(result);
        var items = dictionaries.getIfAvailable(); var catalog = plans.getIfAvailable();
        if (items == null || catalog == null) return List.copyOf(result);
        String id = sourceId.substring(prefix.length(), sourceId.length() - suffix.length()).replace("~1", "/").replace("~0", "~");
        try (var scope = TenantContext.bypassTenantFilter("resolve committed dictionary presentation dependencies")) {
            var item = items.select(id);
            if (item == null) return List.copyOf(result);
            var category = items.category(item.getCategoryId());
            var binding = OptionBinding.dictionary(category.getApplicationAlias(), category.getAlias());
            for (var plan : catalog.installedPlans().values()) if (uses(plan.uiDescriptor(), binding) ||
                    plan.querySchema().fields().stream().anyMatch(field -> binding.equals(field.optionBinding())))
                result.add(plan.moduleAlias());
        }
        return List.copyOf(result);
    }
    private boolean uses(ResolvedModuleUiDescriptor descriptor, OptionBinding binding) {
        if (descriptor == null) return false;
        var views = new java.util.ArrayList<ResolvedViewDescriptor>();
        views.add(descriptor.defaultEditor());
        descriptor.editorSurfaces().forEach(surface -> views.add(surface.editor()));
        descriptor.editorContributions().forEach(contribution -> views.add(contribution.editor()));
        if (descriptor.page() != null) {
            if (descriptor.page().list() != null) views.add(descriptor.page().list().fields());
            if (descriptor.page().detail() != null) { views.add(descriptor.page().detail().editor()); views.add(descriptor.page().detail().display()); }
        }
        return views.stream().filter(java.util.Objects::nonNull).flatMap(view -> view.fields().stream())
                .anyMatch(field -> field.option() != null && binding.equals(field.option().binding()));
    }
}
