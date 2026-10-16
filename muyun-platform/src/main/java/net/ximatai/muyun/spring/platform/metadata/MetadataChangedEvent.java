package net.ximatai.muyun.spring.platform.metadata;

/** Metadata, field or binding change; moduleAlias preserves a removed binding's owner, tenantId its tenant. */
public record MetadataChangedEvent(String metadataId, String moduleAlias, String tenantId) {
    public MetadataChangedEvent(String metadataId, String moduleAlias) {
        this(metadataId, moduleAlias, null);
    }
}
