package net.ximatai.muyun.spring.web;

/** Authorized scope before business-text filtering, including records untranslated results could omit. */
public record WebBusinessContentQueryStatus(String language, long missingRecords,
        java.util.Map<String, java.util.Map<String, String>> readings, GenerationScope generationScope) {
    public WebBusinessContentQueryStatus(String language, long missingRecords, java.util.Map<String, java.util.Map<String, String>> readings) {
        this(language, missingRecords, readings, null);
    }
    public WebBusinessContentQueryStatus(String language, long missingRecords) { this(language, missingRecords, java.util.Map.of()); }
    public WebBusinessContentQueryStatus {
        var copy = new java.util.LinkedHashMap<String, java.util.Map<String, String>>();
        if (readings != null) readings.forEach((id, fields) -> copy.put(id, java.util.Map.copyOf(fields)));
        readings = java.util.Map.copyOf(copy);
        if (language == null || missingRecords < 0)
            throw new IllegalArgumentException("Invalid business content query status");
    }
    /** Routing facts for the actual source of this page. Every request still requires live authorization. */
    public record GenerationScope(String moduleAlias, String parentId, String relationCode) {
        public GenerationScope {
            net.ximatai.muyun.spring.common.util.PlatformNameRules.requireModuleAlias(moduleAlias);
            if ((parentId == null) != (relationCode == null)
                    || parentId != null && (parentId.isBlank() || parentId.length() > 64 || relationCode.isBlank() || relationCode.length() > 128))
                throw new IllegalArgumentException("Invalid business content generation source");
        }
        public static GenerationScope root(String moduleAlias) { return new GenerationScope(moduleAlias, null, null); }
    }
}
