package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.PlatformAction;

/** Shared HTTP semantics for a record that is absent or outside the caller's readable scope. */
public final class RecordReadSupport {
    private RecordReadSupport() {
    }

    public static <T extends EntityContract> T requireVisible(String moduleAlias, String recordId, T record) {
        if (record != null) {
            return record;
        }
        throw PlatformErrors.notFound("record not found: " + recordId,
                ErrorScope.module(moduleAlias).action(PlatformAction.VIEW.code()));
    }
}
