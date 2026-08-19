package net.ximatai.muyun.spring.platform.web;

import java.util.function.Consumer;

/** The detail slot owns its empty state and its editor field declaration. */
public record PageDetailDefinition(String emptyDescription, String createTitle, ViewDefinition display,
                                   ViewDefinition editor, PageDetailWorkspaceViewDefinition workspaceView,
                                   boolean showSystemInfo) {
    public PageDetailDefinition(String emptyDescription, String createTitle, ViewDefinition display,
                                ViewDefinition editor, PageDetailWorkspaceViewDefinition workspaceView) {
        this(emptyDescription, createTitle, display, editor, workspaceView, true);
    }

    public PageDetailDefinition(String emptyDescription, String createTitle, ViewDefinition display,
                                ViewDefinition editor) {
        this(emptyDescription, createTitle, display, editor, null, true);
    }
    public PageDetailDefinition {
        emptyDescription = emptyDescription == null || emptyDescription.isBlank() ? "请选择记录" : emptyDescription.trim();
        createTitle = createTitle == null || createTitle.isBlank() ? "新建记录" : createTitle.trim();
        if (editor != null && editor.viewKind() != ModuleViewKind.FORM) {
            throw new IllegalArgumentException("page detail editor must be a form view");
        }
        if (display != null && display.viewKind() != ModuleViewKind.FORM) {
            throw new IllegalArgumentException("page detail display must be a form view");
        }
    }

    public static final class Builder {
        private String emptyDescription;
        private String createTitle;
        private ViewDefinition display;
        private ViewDefinition editor;
        private PageDetailWorkspaceViewDefinition workspaceView;
        private boolean showSystemInfo = true;

        public Builder emptyDescription(String value) { emptyDescription = value; return this; }
        public Builder createTitle(String value) { createTitle = value; return this; }
        /** Controls the standard immutable record metadata section beneath the detail content. */
        public Builder showSystemInfo(boolean value) { showSystemInfo = value; return this; }
        public Builder display(Consumer<ViewDefinition.Builder> customizer) {
            ViewDefinition.Builder builder = ViewDefinition.form("page_detail_display");
            if (customizer != null) customizer.accept(builder);
            display = builder.build();
            return this;
        }
        public Builder editor(Consumer<ViewDefinition.Builder> customizer) {
            ViewDefinition.Builder builder = ViewDefinition.form("page_detail_editor");
            if (customizer != null) customizer.accept(builder);
            editor = builder.build();
            return this;
        }
        /**
         * Enables the standard "open in a new tab" detail action after the
         * frontend registers an implementation for this stable view type.
         */
        public Builder workspaceView(String type) {
            workspaceView = new PageDetailWorkspaceViewDefinition(type);
            return this;
        }
        PageDetailDefinition build() {
            return new PageDetailDefinition(emptyDescription, createTitle, display, editor, workspaceView, showSystemInfo);
        }
    }
}
