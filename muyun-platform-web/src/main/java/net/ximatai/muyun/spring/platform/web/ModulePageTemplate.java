package net.ximatai.muyun.spring.platform.web;

/**
 * A standard page shell selected by a UI descriptor. Templates own interaction layout;
 * modules only provide their views, fields and capabilities as slot content.
 */
public enum ModulePageTemplate {
    FLAT_MANAGEMENT,
    LIST_DETAIL_CARD,
    TREE_MANAGEMENT
}
