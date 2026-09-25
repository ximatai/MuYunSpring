package net.ximatai.muyun.spring.platform.web;

import java.util.List;
import net.ximatai.muyun.spring.platform.ui.PageCompositionSaveCommand;

/** Transient page tree and unsaved input fields; preview never persists either. */
public record PresentationRevisionPreviewRequest(String uiTreeJson, List<PageCompositionSaveCommand.NewField> newFields, List<PageCompositionSaveCommand.NewChild> newChildren) {
    public PresentationRevisionPreviewRequest { newFields = newFields == null ? List.of() : List.copyOf(newFields); newChildren = newChildren == null ? List.of() : List.copyOf(newChildren); }
    public PresentationRevisionPreviewRequest(String uiTreeJson, List<PageCompositionSaveCommand.NewField> newFields) { this(uiTreeJson, newFields, List.of()); }
    public PresentationRevisionPreviewRequest(String uiTreeJson) { this(uiTreeJson, List.of()); }
}
