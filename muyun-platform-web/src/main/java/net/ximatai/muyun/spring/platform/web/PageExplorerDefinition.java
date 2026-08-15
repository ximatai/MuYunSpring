package net.ximatai.muyun.spring.platform.web;

/** Visible content in the explorer slot of {@link ModulePageTemplate#FLAT_MANAGEMENT}. */
public record PageExplorerDefinition(String title, String searchPlaceholder, String emptyDescription,
                                     String recordLabel, String fallbackTitle, String titleField,
                                     String secondaryField, boolean mutedWhenDisabled) {
    public PageExplorerDefinition {
        title = value(title, "记录列表");
        searchPlaceholder = value(searchPlaceholder, "搜索名称、编码或 ID");
        emptyDescription = value(emptyDescription, "暂无记录");
        recordLabel = value(recordLabel, "记录");
        fallbackTitle = value(fallbackTitle, "未命名记录");
        titleField = value(titleField, "title");
        secondaryField = secondaryField == null || secondaryField.isBlank() ? null : secondaryField.trim();
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String title;
        private String searchPlaceholder;
        private String emptyDescription;
        private String recordLabel;
        private String fallbackTitle;
        private String titleField;
        private String secondaryField;
        private boolean mutedWhenDisabled;

        public Builder title(String value) { title = value; return this; }
        public Builder searchPlaceholder(String value) { searchPlaceholder = value; return this; }
        public Builder emptyDescription(String value) { emptyDescription = value; return this; }
        public Builder recordLabel(String value) { recordLabel = value; return this; }
        public Builder fallbackTitle(String value) { fallbackTitle = value; return this; }
        public Builder titleField(String value) { titleField = value; return this; }
        public Builder secondaryField(String value) { secondaryField = value; return this; }
        public Builder mutedWhenDisabled() { mutedWhenDisabled = true; return this; }
        public PageExplorerDefinition build() {
            return new PageExplorerDefinition(title, searchPlaceholder, emptyDescription, recordLabel, fallbackTitle,
                    titleField, secondaryField, mutedWhenDisabled);
        }
    }
}
