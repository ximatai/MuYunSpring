package net.ximatai.muyun.spring.platform.ui;

public record PlatformAssociationBlock(
        String uiConfigId,
        String key,
        String viewCode,
        String title,
        String targetUiConfigId,
        String queryTemplateId,
        String queryPath,
        ResolvedDetailRelationDescriptor relation
) {
    public PlatformAssociationBlock(String uiConfigId, String key, String viewCode, String title,
                                    String targetUiConfigId, String queryTemplateId, String queryPath) {
        this(uiConfigId, key, viewCode, title, targetUiConfigId, queryTemplateId, queryPath, null);
    }
    public PlatformAssociationBlock {
        uiConfigId = normalize(uiConfigId);
        key = normalize(key);
        viewCode = requireText(viewCode, "association block viewCode");
        title = normalize(title);
        targetUiConfigId = normalize(targetUiConfigId);
        queryTemplateId = normalize(queryTemplateId);
        queryPath = requireText(queryPath, "association block queryPath");
    }

    private static String requireText(String value, String name) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
