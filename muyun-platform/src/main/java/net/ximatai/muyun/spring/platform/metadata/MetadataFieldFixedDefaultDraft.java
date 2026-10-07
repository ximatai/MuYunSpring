package net.ximatai.muyun.spring.platform.metadata;

/** A relation-scoped fixed initial value; an absent draft preserves the existing behavior. */
public record MetadataFieldFixedDefaultDraft(String value, Integer expectedConfigVersion) {
}
