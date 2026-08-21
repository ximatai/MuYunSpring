package net.ximatai.muyun.spring.platform.ui;

/** Source-neutral editing and persistence semantics for a managed direct relation. */
public record ResolvedDetailRelationEditing(Mode mode, SaveMode saveMode, boolean recycleBinEnabled) {
    public enum Mode { DIALOG, INLINE }
    public enum SaveMode { INDEPENDENT, AGGREGATE_DRAFT }

    public static final ResolvedDetailRelationEditing DEFAULT =
            new ResolvedDetailRelationEditing(Mode.DIALOG, SaveMode.INDEPENDENT, false);

    public ResolvedDetailRelationEditing(Mode mode, SaveMode saveMode) {
        this(mode, saveMode, false);
    }

    public ResolvedDetailRelationEditing {
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
