package net.ximatai.muyun.spring.platform.metadata;

/** Configuration fact; moduleAlias identifies a removed MAIN relation, tenantId its owner. */
public record MetadataChangedEvent(String metadataId, String moduleAlias, String tenantId) {
    public MetadataChangedEvent(String metadataId, String moduleAlias) {
        this(metadataId, moduleAlias, null);
    }
}
