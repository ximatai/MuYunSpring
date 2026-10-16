package net.ximatai.muyun.spring.web;

/** Authorized scope before business-text filtering, including records untranslated results could omit. */
public record WebBusinessContentQueryStatus(String language, long missingRecords,
        java.util.Map<String, java.util.Map<String, String>> readings) {
    public WebBusinessContentQueryStatus(String language, long missingRecords) { this(language, missingRecords, java.util.Map.of()); }
    public WebBusinessContentQueryStatus {
        var copy = new java.util.LinkedHashMap<String, java.util.Map<String, String>>();
        if (readings != null) readings.forEach((id, fields) -> copy.put(id, java.util.Map.copyOf(fields)));
        readings = java.util.Map.copyOf(copy);
        if (language == null || missingRecords < 0)
            throw new IllegalArgumentException("Invalid business content query status");
    }
}
