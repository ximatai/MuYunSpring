package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.common.util.Preconditions;

public record GrantableAction(
        String moduleAlias,
        String actionCode,
        String permissionActionCode,
        String title,
        String titleKey,
        boolean actionAuth,
        boolean dataAuth
) {
    public GrantableAction {
        moduleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        actionCode = Preconditions.requireText(actionCode, "actionCode");
        permissionActionCode = permissionActionCode == null || permissionActionCode.isBlank()
                ? actionCode
                : Preconditions.requireText(permissionActionCode, "permissionActionCode");
        title = title == null || title.isBlank() ? actionCode : title.trim();
        titleKey = titleKey == null || titleKey.isBlank() ? null : titleKey.trim();
    }

    public GrantableAction(String moduleAlias,
                           String actionCode,
                           String permissionActionCode,
                           String title,
                           boolean actionAuth,
                           boolean dataAuth) {
        this(moduleAlias, actionCode, permissionActionCode, title, null, actionAuth, dataAuth);
    }

    public static GrantableAction ofPlatformDefaults(String moduleAlias, PlatformAction action) {
        return new GrantableAction(
                moduleAlias,
                action.code(),
                action.permissionActionCode(),
                action.title(),
                action.titleKey(),
                action.actionAuth(),
                action.dataAuth()
        );
    }
}
