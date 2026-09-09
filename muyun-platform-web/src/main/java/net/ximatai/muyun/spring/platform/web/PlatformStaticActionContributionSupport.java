package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;

public final class PlatformStaticActionContributionSupport {
    private PlatformStaticActionContributionSupport() {
    }

    public static String targetModule(PlatformStaticActionContribution contribution) {
        return PlatformNameRules.requireModuleAlias(contribution.targetModule());
    }

    public static String actionCode(PlatformStaticActionContribution contribution, PlatformAction action) {
        return actionCode(contribution, action.code());
    }

    public static String permissionActionCode(PlatformStaticActionContribution contribution, PlatformAction action) {
        return actionCode(contribution, action.permissionActionCode());
    }

    public static String actionCode(PlatformStaticActionContribution contribution, String operation) {
        String resource = PlatformNameRules.requireIdentifier(contribution.resource(), "resource");
        String validOperation = PlatformNameRules.requireActionCode(operation, "operation");
        return PlatformNameRules.requireActionCode(resource + "_" + validOperation, "actionCode");
    }

    public static String title(PlatformStaticActionContribution contribution, PlatformAction action) {
        String resourceTitle = resourceTitle(contribution);
        return switch (action) {
            case QUERY -> "查询" + resourceTitle;
            case TREE -> "查看" + resourceTitle + "树";
            case VIEW -> "查看" + resourceTitle;
            case CREATE -> "新增" + resourceTitle;
            case UPDATE -> "修改" + resourceTitle;
            case DELETE, BATCH_DELETE -> "删除" + resourceTitle;
            case ENABLE -> "启用" + resourceTitle;
            case DISABLE -> "停用" + resourceTitle;
            case RECYCLE_BIN_QUERY -> "查询" + resourceTitle + "回收站";
            case RECYCLE_BIN_RESTORE -> "恢复" + resourceTitle;
            case RECYCLE_BIN_PURGE -> "彻底清理" + resourceTitle;
            case SORT -> "调整" + resourceTitle + "排序";
            case IMPORT -> "导入" + resourceTitle;
            case EXPORT -> "导出" + resourceTitle;
            case MENU, REFERENCE, MANAGE_PERMISSIONS -> action.title() + " " + resourceTitle;
        };
    }

    public static String title(PlatformStaticActionContribution contribution, String fallback) {
        return fallback == null || fallback.isBlank()
                ? PlatformNameRules.requireIdentifier(contribution.resource(), "resource") + " action"
                : fallback.trim();
    }

    private static String resourceTitle(PlatformStaticActionContribution contribution) {
        String title = contribution.resourceTitle();
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("resourceTitle must not be blank");
        }
        return title.trim();
    }
}
