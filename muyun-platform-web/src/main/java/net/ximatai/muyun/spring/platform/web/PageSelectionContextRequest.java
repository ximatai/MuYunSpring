package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;

/** Input supplied to a trusted selection resolver for one page request. */
public record PageSelectionContextRequest(String moduleAlias,
                                          String selectionKind,
                                          String selectionKey,
                                          PlatformAction action,
                                          CurrentUser currentUser,
                                          String menuId) {
    public PageSelectionContextRequest {
        moduleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        selectionKind = PlatformNameRules.requireFieldName(selectionKind, "page selection kind");
        if (selectionKey == null || selectionKey.isBlank()) {
            throw new IllegalArgumentException("page selection key must not be blank");
        }
        if (action == null) throw new IllegalArgumentException("page selection action must not be null");
        if (currentUser == null) throw new IllegalArgumentException("page selection current user must not be null");
    }
}
