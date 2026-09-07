package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationListField;

/** Page-owned presentation overrides for a relation column; source field semantics stay intact. */
public record PageDetailRelationColumnProperties(String label, Integer width, String align) {
    public PageDetailRelationColumnProperties {
        label = label == null || label.isBlank() ? null : label.trim();
        if (width != null && width <= 0) throw new IllegalArgumentException("relation column width must be positive");
        if (align != null && !java.util.Set.of("left", "center", "right").contains(align)) {
            throw new IllegalArgumentException("invalid relation column alignment");
        }
    }

    public ResolvedDetailRelationListField applyTo(ResolvedDetailRelationListField field) {
        return new ResolvedDetailRelationListField(field.fieldName(), label == null ? field.title() : label,
                field.fieldForm(), field.fieldUiControlAlias(), field.valueType(),
                width == null ? field.width() : width, align == null ? field.align() : align,
                field.maxDisplayLines());
    }
}
