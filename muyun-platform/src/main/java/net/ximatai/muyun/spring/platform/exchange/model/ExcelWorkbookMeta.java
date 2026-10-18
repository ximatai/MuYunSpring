package net.ximatai.muyun.spring.platform.exchange.model;

public record ExcelWorkbookMeta(
        String protocolVersion,
        String moduleAlias,
        String uiConfigId,
        String uiConfigTitle,
        String timeZone,
        String documentId,
        String purpose,
        String language
) {
    public ExcelWorkbookMeta(String protocolVersion, String moduleAlias, String uiConfigId, String uiConfigTitle, String timeZone) {
        this(protocolVersion, moduleAlias, uiConfigId, uiConfigTitle, timeZone, null, null, null);
    }
}
