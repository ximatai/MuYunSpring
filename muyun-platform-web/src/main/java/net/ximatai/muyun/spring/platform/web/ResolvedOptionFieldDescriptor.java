package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.OptionItem;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;

import java.util.List;

/**
 * Source-neutral option-field facts exposed to a resolved UI descriptor.
 * Actual options stay runtime data because dictionary scope and enabled state are tenant-sensitive.
 */
public record ResolvedOptionFieldDescriptor(OptionBinding binding,
                                            OptionSelectionMode selectionMode,
                                            String titleField,
                                            List<OptionItem> inlineItems) {
    public ResolvedOptionFieldDescriptor {
        if (binding == null) {
            throw new IllegalArgumentException("option binding must not be null");
        }
        selectionMode = selectionMode == null ? OptionSelectionMode.SINGLE : selectionMode;
        titleField = titleField == null || titleField.isBlank() ? null : titleField.trim();
        inlineItems = inlineItems == null ? List.of() : List.copyOf(inlineItems);
    }

    /** Compatibility constructor for sources that only expose option binding facts. */
    public ResolvedOptionFieldDescriptor(OptionBinding binding, OptionSelectionMode selectionMode, String titleField) {
        this(binding, selectionMode, titleField, List.of());
    }
}
