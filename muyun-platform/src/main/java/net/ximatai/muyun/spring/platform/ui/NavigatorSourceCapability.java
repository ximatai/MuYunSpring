package net.ximatai.muyun.spring.platform.ui;

/**
 * A read-only projection a module deliberately exposes for use by another page's navigator.
 *
 * <p>This is a page-delivery capability, not an entity capability: a tree-shaped entity is not
 * automatically safe or intended to be read as a navigator tree.</p>
 */
public enum NavigatorSourceCapability {
    REFERENCE_QUERY,
    REFERENCE_TREE,
    REFERENCE_TREE_SORT
}
