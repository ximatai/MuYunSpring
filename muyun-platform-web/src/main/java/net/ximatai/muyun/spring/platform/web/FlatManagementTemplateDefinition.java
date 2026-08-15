package net.ximatai.muyun.spring.platform.web;

/**
 * Content supplied to the standard flat-management page template.
 *
 * <p>The template owns its explorer, recycle-bin and detail interaction. A module only supplies
 * the vocabulary that is visible in those stable slots.</p>
 */
public record FlatManagementTemplateDefinition(String explorerTitle,
                                               String explorerSearchPlaceholder,
                                               String emptyDescription,
                                               String detailEmptyDescription,
                                               String createTitle,
                                               String recordLabel,
                                               String fallbackTitle) {
    public FlatManagementTemplateDefinition {
        explorerTitle = required(explorerTitle, "记录列表");
        explorerSearchPlaceholder = required(explorerSearchPlaceholder, "搜索名称、编码或 ID");
        emptyDescription = required(emptyDescription, "暂无记录");
        detailEmptyDescription = required(detailEmptyDescription, "请选择记录，或新建记录");
        createTitle = required(createTitle, "新建记录");
        recordLabel = required(recordLabel, "记录");
        fallbackTitle = required(fallbackTitle, "未命名记录");
    }

    private static String required(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String explorerTitle;
        private String explorerSearchPlaceholder;
        private String emptyDescription;
        private String detailEmptyDescription;
        private String createTitle;
        private String recordLabel;
        private String fallbackTitle;

        public Builder explorerTitle(String value) { explorerTitle = value; return this; }
        public Builder explorerSearchPlaceholder(String value) { explorerSearchPlaceholder = value; return this; }
        public Builder emptyDescription(String value) { emptyDescription = value; return this; }
        public Builder detailEmptyDescription(String value) { detailEmptyDescription = value; return this; }
        public Builder createTitle(String value) { createTitle = value; return this; }
        public Builder recordLabel(String value) { recordLabel = value; return this; }
        public Builder fallbackTitle(String value) { fallbackTitle = value; return this; }

        public FlatManagementTemplateDefinition build() {
            return new FlatManagementTemplateDefinition(explorerTitle, explorerSearchPlaceholder, emptyDescription,
                    detailEmptyDescription, createTitle, recordLabel, fallbackTitle);
        }
    }
}
