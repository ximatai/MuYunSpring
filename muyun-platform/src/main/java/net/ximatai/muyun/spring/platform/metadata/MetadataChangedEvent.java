package net.ximatai.muyun.spring.platform.metadata;

/** Transactional configuration fact; moduleAlias also identifies a removed MAIN relation. */
public record MetadataChangedEvent(String metadataId, String moduleAlias) {
}
