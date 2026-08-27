package net.ximatai.muyun.spring.platform.web;

/**
 * Resolves an opaque selection key into server-authoritative page fields.
 *
 * <p>Implementations must authorize the current user for the module, menu, action and selected
 * business range on every call. They must never treat values decoded from the browser as fields
 * that may be queried or written directly.</p>
 */
public interface PageSelectionContextResolver {
    String selectionKind();

    ResolvedPageSelectionContext resolve(PageSelectionContextRequest request);
}
