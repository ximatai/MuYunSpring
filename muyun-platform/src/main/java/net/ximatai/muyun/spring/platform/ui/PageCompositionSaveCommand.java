package net.ximatai.muyun.spring.platform.ui;

import java.util.List;

/** Unsaved input components and their page placements are committed together. */
public record PageCompositionSaveCommand(PlatformPresentationRevision revision, String relationId,
                                         Integer expectedMetadataVersion, List<NewField> newFields, List<NewChild> newChildren) {
    public PageCompositionSaveCommand {
        newFields = newFields == null ? List.of() : List.copyOf(newFields);
        newChildren = newChildren == null ? List.of() : List.copyOf(newChildren);
    }
    public PageCompositionSaveCommand(PlatformPresentationRevision revision, String relationId,
                                     Integer expectedMetadataVersion, List<NewField> newFields) {
        this(revision, relationId, expectedMetadataVersion, newFields, List.of());
    }
    public record NewChild(String key, String title, List<NewField> fields) {}
    public record NewField(String key, String title, String component, Boolean required, String suggestedName) {
        public NewField(String key, String title, String component, Boolean required) {
            this(key, title, component, required, null);
        }
        public NewField(String key, String title, String component) {
            this(key, title, component, false, null);
        }
    }
}
