package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBinding;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlProperty;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlPresetCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Compiles configured controls into the descriptor contract consumed by page bootstrap. */
final class FieldControlDescriptorCatalog {
    private FieldControlDescriptorCatalog() { }

    /**
     * The browser form runtime can execute only these renderer facts.  This is deliberately kept
     * next to the bootstrap catalog: publishing a control is an execution promise, not merely a
     * metadata preference.  New renderer kinds must be added here and to the web registry in the
     * same change, with component and payload-contract coverage.
     */
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
            if (!FieldUiControlPresetCatalog.WEB_FORM_EXECUTABLE_RENDERERS.contains(control.getRendererType())) {
                throw new IllegalArgumentException("enabled field control has no executable web renderer: "
                        + control.getAlias() + "." + control.getRendererType().name());
            }
            if (control.getValueShape() != net.ximatai.muyun.spring.platform.metadata.FieldUiControlValueShape.SCALAR
                    && !(control.getRendererType() == net.ximatai.muyun.spring.dynamic.metadata.ViewControlType.MULTI_SELECT
                    && control.getValueShape() == net.ximatai.muyun.spring.platform.metadata.FieldUiControlValueShape.COLLECTION)) {
                throw new IllegalArgumentException("enabled field control has no executable web value shape: "
                        + control.getAlias() + "." + control.getValueShape());
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
                Map.entry("recordPicker", "RECORD_PICKER"), Map.entry("reference", "RECORD_PICKER"), Map.entry("enabledStatus", "ENABLED_STATUS"),
                Map.entry("booleanStatus", "BOOLEAN_STATUS"), Map.entry("tagList", "TAG_LIST"));
        return renderers.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                entry -> new ResolvedFieldControlDescriptor(entry.getKey(), entry.getValue(),
                        "multi_select".equals(entry.getKey()) ? "COLLECTION" : "SCALAR", Map.of(), List.of())));
    }
}
