package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.PlatformAction;

/**
 * Optional business-owned restrictions for a named menu entry of an otherwise standard module page.
 *
 * <p>The platform resolves and authenticates the entry context. This policy intentionally does not
 * define domain fields, query values, or business actions; the owning module supplies only the
 * restrictions that distinguish one of its entries from another.</p>
 */
public interface ModulePageEntryPolicy<T extends EntityContract> {
    /** Whether this policy owns the resolved entry. */
    boolean supports(MenuEntryRequestContext entry);

    /** Appends an entry-owned mandatory list condition. */
    default void appendQueryCriteria(MenuEntryRequestContext entry, Criteria criteria) {
    }

    /** Rejects a standard or business action that this entry does not expose. */
    default void requireAction(MenuEntryRequestContext entry, PlatformAction action) {
    }

    /** Rejects a record that does not belong to this entry's fixed scope. */
    default void requireRecord(MenuEntryRequestContext entry, PlatformAction action, T record) {
    }
}
