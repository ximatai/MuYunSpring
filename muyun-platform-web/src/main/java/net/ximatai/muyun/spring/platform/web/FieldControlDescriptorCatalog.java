package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBinding;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Compiles configured controls into the descriptor contract consumed by page bootstrap. */
final class FieldControlDescriptorCatalog {
    private FieldControlDescriptorCatalog() { }

    static Map<String, ResolvedFieldControlDescriptor> fromConfigured(List<FieldUiControl> controls,
                                                                        List<FieldUiControlProperty> properties,
                                                                        List<FieldUiControlBinding> bindings) {
        Map<String, List<FieldUiControlProperty>> propertiesByAlias = (properties == null ? List.<FieldUiControlProperty>of() : properties).stream()
                .collect(Collectors.groupingBy(FieldUiControlProperty::getFieldUiControlAlias, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<FieldUiControlBinding>> bindingsByAlias = (bindings == null ? List.<FieldUiControlBinding>of() : bindings).stream()
                .collect(Collectors.groupingBy(FieldUiControlBinding::getFieldUiControlAlias, LinkedHashMap::new, Collectors.toList()));
        LinkedHashMap<String, ResolvedFieldControlDescriptor> result = new LinkedHashMap<>();
        for (FieldUiControl control : controls == null ? List.<FieldUiControl>of() : controls) {
            if (control == null || !Boolean.TRUE.equals(control.getEnabled())) continue;
            if (control.getRendererType() == null) {
                throw new IllegalArgumentException("enabled field control requires renderer type: " + control.getAlias());
            }
            Map<String, String> resolvedProperties = propertiesByAlias.getOrDefault(control.getAlias(), List.of()).stream()
                    .filter(property -> property.getDefaultValue() != null)
                    .collect(Collectors.toMap(FieldUiControlProperty::getAttributeAlias, FieldUiControlProperty::getDefaultValue,
                            (left, ignored) -> left, LinkedHashMap::new));
            List<ResolvedFieldControlBindingDescriptor> resolvedBindings = bindingsByAlias.getOrDefault(control.getAlias(), List.of()).stream()
                    .map(binding -> new ResolvedFieldControlBindingDescriptor(binding.getValueKey(), binding.getValueFieldSpecAlias()))
                    .toList();
            result.put(control.getAlias(), new ResolvedFieldControlDescriptor(control.getAlias(),
                    control.getRendererType().name(), control.getValueShape().name(), resolvedProperties, resolvedBindings));
        }
        return Map.copyOf(result);
    }

    static Map<String, ResolvedFieldControlDescriptor> standard() {
        Map<String, String> renderers = Map.ofEntries(
                Map.entry("text", "TEXT"), Map.entry("input", "TEXT"), Map.entry("textarea", "TEXTAREA"), Map.entry("number", "DECIMAL"),
                Map.entry("integer", "NUMBER"), Map.entry("amount", "DECIMAL"), Map.entry("percentage", "DECIMAL"),
                Map.entry("switch", "SWITCH"), Map.entry("select", "SELECT"), Map.entry("multi_select", "MULTI_SELECT"),
                Map.entry("date", "DATE"), Map.entry("datetime", "DATETIME"), Map.entry("json", "JSON"),
                Map.entry("date_range", "DATE"), Map.entry("date_time_range", "DATETIME"),
                Map.entry("date_time_with_time_zone", "DATETIME"),
                Map.entry("recordPicker", "RECORD_PICKER"), Map.entry("reference", "RECORD_PICKER"), Map.entry("enabledStatus", "ENABLED_STATUS"),
                Map.entry("booleanStatus", "BOOLEAN_STATUS"), Map.entry("tagList", "TAG_LIST"));
        return renderers.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                entry -> new ResolvedFieldControlDescriptor(entry.getKey(), entry.getValue(),
                        "multi_select".equals(entry.getKey()) ? "COLLECTION"
                                : composite(entry.getKey()) ? "COMPOSITE" : "SCALAR", Map.of(), standardBindings(entry.getKey()))));
    }

    private static boolean composite(String alias) {
        return "date_range".equals(alias) || "date_time_range".equals(alias)
                || "date_time_with_time_zone".equals(alias);
    }

    private static List<ResolvedFieldControlBindingDescriptor> standardBindings(String alias) {
        return switch (alias) {
            case "date_range" -> List.of(new ResolvedFieldControlBindingDescriptor("end", "date"));
            case "date_time_range" -> List.of(new ResolvedFieldControlBindingDescriptor("end", "datetime"));
            case "date_time_with_time_zone" -> List.of(new ResolvedFieldControlBindingDescriptor("timeZone", "string"));
            default -> List.of();
        };
    }
}
