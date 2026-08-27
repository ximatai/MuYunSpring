package net.ximatai.muyun.spring.platform.web;

import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

import java.util.List;

/** Applies declared server-authoritative page context before a standard mutation. */
final class PageContextMutationConstraints {
    private PageContextMutationConstraints() {
    }

    static <T> T applyForCreate(T record, List<PageContextBindingDefinition> bindings) {
        apply(record, bindings, false, null, null, null);
        return record;
    }

    static <T> T applyForUpdate(T record, T existing, List<PageContextBindingDefinition> bindings) {
        apply(existing, bindings, true, null, null, null);
        apply(record, bindings, false, null, null, null);
        return record;
    }

    static <T> T applyForCreate(T record, List<PageContextBindingDefinition> bindings,
                                String moduleAlias, net.ximatai.muyun.spring.common.platform.PlatformAction action,
                                PageSelectionContextResolverRegistry selectionResolvers) {
        apply(record, bindings, false, moduleAlias, action, selectionResolvers);
        return record;
    }

    static <T> T applyForUpdate(T record, T existing, List<PageContextBindingDefinition> bindings,
                                String moduleAlias, net.ximatai.muyun.spring.common.platform.PlatformAction action,
                                PageSelectionContextResolverRegistry selectionResolvers) {
        apply(existing, bindings, true, moduleAlias, action, selectionResolvers);
        apply(record, bindings, false, moduleAlias, action, selectionResolvers);
        return record;
    }

    private static void apply(Object record, List<PageContextBindingDefinition> bindings, boolean verifyOnly,
                              String moduleAlias, net.ximatai.muyun.spring.common.platform.PlatformAction action,
                              PageSelectionContextResolverRegistry selectionResolvers) {
        if (record == null) throw new IllegalArgumentException("mutation record must not be null");
        BeanWrapper properties = new BeanWrapperImpl(record);
        for (PageContextBindingDefinition binding : bindings) {
            Object authoritativeValue = binding.source() == PageContextSource.RESOLVED_SELECTION
                    ? PageContextScopePolicy.requiredMutationValue(binding, moduleAlias, action, selectionResolvers).value()
                    : PageContextServerValueResolver.resolve(binding).orElseThrow(() ->
                    new IllegalStateException("cannot resolve page mutation context: " + binding.sourceKey()));
            if (!properties.isReadableProperty(binding.targetKey())) {
                throw new IllegalArgumentException("mutation context field is not readable: " + binding.targetKey());
            }
            Object currentValue = properties.getPropertyValue(binding.targetKey());
            if (verifyOnly) {
                if (!java.util.Objects.equals(currentValue, authoritativeValue)) {
                    throw new IllegalArgumentException("record does not belong to the current page context: "
                            + binding.targetKey());
                }
            } else {
                if (!properties.isWritableProperty(binding.targetKey())) {
                    throw new IllegalArgumentException("mutation context field is not writable: " + binding.targetKey());
                }
                properties.setPropertyValue(binding.targetKey(), authoritativeValue);
            }
        }
    }
}
