package net.ximatai.muyun.spring.platform.web;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A semantic form section that owns a contiguous set of fields. */
public record FormGroupDefinition(String groupCode, String title, String subtitle, List<ViewFieldDefinition> fields) {
    public FormGroupDefinition {
        if (groupCode == null || !groupCode.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("form group code must be lower snake case");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("form group title must not be blank");
        }
        groupCode = groupCode.trim();
        title = title.trim();
        subtitle = subtitle == null || subtitle.isBlank() ? null : subtitle.trim();
        fields = fields == null || fields.isEmpty() ? List.of() : List.copyOf(fields);
    }

    public static Builder builder(String groupCode, String title, String subtitle) {
        return new Builder(groupCode, title, subtitle);
    }

    public static final class Builder {
        private final String groupCode;
        private final String title;
        private final String subtitle;
        private final List<ViewFieldDefinition> fields = new ArrayList<>();

        private Builder(String groupCode, String title, String subtitle) {
            this.groupCode = groupCode;
            this.title = title;
            this.subtitle = subtitle;
        }

        public Builder field(String fieldName, Consumer<ViewFieldDefinition.Builder> customizer) {
            ViewFieldDefinition.Builder builder = ViewFieldDefinition.field(fieldName);
            if (customizer != null) {
                customizer.accept(builder);
            }
            fields.add(builder.build());
            return this;
        }

        public FormGroupDefinition build() {
            return new FormGroupDefinition(groupCode, title, subtitle, fields);
        }
    }
}
