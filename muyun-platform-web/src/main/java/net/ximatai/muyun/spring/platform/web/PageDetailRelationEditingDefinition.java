package net.ximatai.muyun.spring.platform.web;

/** Declares how a mutable managed relation participates in its parent editor. */
public record PageDetailRelationEditingDefinition(Mode mode, SaveMode saveMode, boolean recycleBinEnabled) {
    public enum Mode { DIALOG, INLINE }
    public enum SaveMode { INDEPENDENT, AGGREGATE_DRAFT }

    public static final PageDetailRelationEditingDefinition DEFAULT =
            new PageDetailRelationEditingDefinition(Mode.DIALOG, SaveMode.INDEPENDENT, false);

    public static PageDetailRelationEditingDefinition aggregateInline() {
        return aggregateInline(false);
    }

    public static PageDetailRelationEditingDefinition aggregateInline(boolean recycleBinEnabled) {
        return new PageDetailRelationEditingDefinition(Mode.INLINE, SaveMode.AGGREGATE_DRAFT, recycleBinEnabled);
    }

    public PageDetailRelationEditingDefinition(Mode mode, SaveMode saveMode) {
        this(mode, saveMode, false);
    }

    public PageDetailRelationEditingDefinition {
        mode = mode == null ? Mode.DIALOG : mode;
        saveMode = saveMode == null ? SaveMode.INDEPENDENT : saveMode;
        if (saveMode == SaveMode.AGGREGATE_DRAFT && mode != Mode.INLINE) {
            throw new IllegalArgumentException("aggregate relation drafts require inline editing");
        }
        if (recycleBinEnabled && saveMode != SaveMode.AGGREGATE_DRAFT) {
            throw new IllegalArgumentException("relation recycle bin requires aggregate draft editing");
        }
    }
}
