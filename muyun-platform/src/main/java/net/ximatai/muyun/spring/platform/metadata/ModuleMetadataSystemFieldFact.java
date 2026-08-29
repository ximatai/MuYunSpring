package net.ximatai.muyun.spring.platform.metadata;

/** A platform-standard runtime field that is not maintained as an ordinary metadata field. */
public record ModuleMetadataSystemFieldFact(
        String fieldName,
        String title,
        String fieldSpecAlias
) {
}
