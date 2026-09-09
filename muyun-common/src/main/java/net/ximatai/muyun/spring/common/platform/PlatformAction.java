package net.ximatai.muyun.spring.common.platform;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public enum PlatformAction {
    MENU(PlatformActionGroup.MENU, "menu", "platform.action.menu", "菜单访问", "Menu",
            PlatformActionLevel.LIST, 10,
            ActionAccessMode.AUTH_REQUIRED, true, false, ActionDefaultGrantPolicy.NONE, null),

    CREATE(PlatformActionGroup.CRUD, "create", "platform.action.create", "新建", "Create",
            PlatformActionLevel.LIST, 10,
            ActionAccessMode.AUTH_REQUIRED, true, false, ActionDefaultGrantPolicy.NONE, null),
    VIEW(PlatformActionGroup.CRUD, "view", "platform.action.view", "查看", "View",
            PlatformActionLevel.RECORD, 20,
            ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, null),
    UPDATE(PlatformActionGroup.CRUD, "update", "platform.action.update", "编辑", "Update",
            PlatformActionLevel.RECORD, 30,
            ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, null),
    DELETE(PlatformActionGroup.CRUD, "delete", "platform.action.delete", "删除", "Delete",
            PlatformActionLevel.RECORD, 40,
            ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, null),
    BATCH_DELETE(PlatformActionGroup.CRUD, "batchDelete", "platform.action.batch-delete", "批量删除", "Batch Delete",
            PlatformActionLevel.BATCH, 45,
            ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, DELETE),
    QUERY(PlatformActionGroup.CRUD, "query", "platform.action.query", "查询", "Query",
            PlatformActionLevel.LIST, 50,
            ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, VIEW),

    SORT(PlatformActionGroup.SORT, "sort", "platform.action.sort", "排序", "Sort",
            PlatformActionLevel.RECORD, 10,
            ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, null),

    TREE(PlatformActionGroup.TREE, "tree", "platform.action.tree", "查看树", "Tree",
            PlatformActionLevel.LIST, 10,
            ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, VIEW),

    REFERENCE(PlatformActionGroup.REFERENCE, "reference", "platform.action.reference", "引用选择", "Reference",
            PlatformActionLevel.LIST, 10,
            ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, VIEW),

    ENABLE(PlatformActionGroup.ENABLE, "enable", "platform.action.enable", "启用", "Enable",
            PlatformActionLevel.RECORD, 10,
            ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, null),
    DISABLE(PlatformActionGroup.ENABLE, "disable", "platform.action.disable", "停用", "Disable",
            PlatformActionLevel.RECORD, 20,
            ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, ENABLE),

    RECYCLE_BIN_QUERY(PlatformActionGroup.RECYCLE_BIN, "recycleBinQuery", "platform.action.recycle-bin-query",
            "查询回收站", "Recycle Bin Query", PlatformActionLevel.LIST, 10,
            ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, null),
    RECYCLE_BIN_RESTORE(PlatformActionGroup.RECYCLE_BIN, "recycleBinRestore", "platform.action.recycle-bin-restore",
            "恢复回收站数据", "Recycle Bin Restore", PlatformActionLevel.LIST, 20,
            ActionAccessMode.AUTH_REQUIRED, true, false, ActionDefaultGrantPolicy.NONE, null),
    RECYCLE_BIN_PURGE(PlatformActionGroup.RECYCLE_BIN, "recycleBinPurge", "platform.action.recycle-bin-purge",
            "彻底清理回收站数据", "Recycle Bin Purge", PlatformActionLevel.LIST, 30,
            ActionAccessMode.AUTH_REQUIRED, true, false, ActionDefaultGrantPolicy.NONE, null),

    MANAGE_PERMISSIONS(PlatformActionGroup.DATA_SCOPE, "managePermissions", "platform.action.manage-permissions",
            "授权", "Manage Record Permissions", PlatformActionLevel.RECORD, 10,
            ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, null),

    IMPORT(PlatformActionGroup.EXCHANGE, "import", "platform.action.import", "导入", "Import",
            PlatformActionLevel.LIST, 10,
            ActionAccessMode.AUTH_REQUIRED, true, false, ActionDefaultGrantPolicy.NONE, null),
    EXPORT(PlatformActionGroup.EXCHANGE, "export", "platform.action.export", "导出", "Export",
            PlatformActionLevel.LIST, 20,
            ActionAccessMode.AUTH_REQUIRED, true, true, ActionDefaultGrantPolicy.NONE, VIEW);

    private final PlatformActionGroup group;
    private final String code;
    private final String titleKey;
    private final String title;
    private final String legacyTitle;
    private final PlatformActionLevel level;
    private final int order;
    private final ActionAccessMode accessMode;
    private final boolean actionAuth;
    private final boolean dataAuth;
    private final ActionDefaultGrantPolicy defaultGrantPolicy;
    private final PlatformAction permissionAction;

    PlatformAction(PlatformActionGroup group,
                   String code,
                   String titleKey,
                   String title,
                   String legacyTitle,
                   PlatformActionLevel level,
                   int order) {
        this(group, code, titleKey, title, legacyTitle, level, order,
                ActionAccessMode.AUTH_REQUIRED, true, false, ActionDefaultGrantPolicy.NONE, null);
    }

    PlatformAction(PlatformActionGroup group,
                   String code,
                   String titleKey,
                   String title,
                   String legacyTitle,
                   PlatformActionLevel level,
                   int order,
                   ActionAccessMode accessMode,
                   boolean actionAuth,
                   boolean dataAuth,
                   ActionDefaultGrantPolicy defaultGrantPolicy,
                   PlatformAction permissionAction) {
        this.group = group;
        this.code = code;
        this.titleKey = titleKey;
        this.title = title;
        this.legacyTitle = legacyTitle;
        this.level = level;
        this.order = order;
        this.accessMode = accessMode;
        this.actionAuth = actionAuth;
        this.dataAuth = dataAuth;
        this.defaultGrantPolicy = defaultGrantPolicy;
        this.permissionAction = permissionAction;
    }

    public PlatformActionGroup group() {
        return group;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public String titleKey() {
        return titleKey;
    }

    public boolean usesDefaultTitle(String candidate) {
        return candidate == null || candidate.isBlank()
                || title.equals(candidate.trim())
                || legacyTitle.equals(candidate.trim());
    }

    public PlatformActionLevel level() {
        return level;
    }

    public int order() {
        return order;
    }

    public ActionAccessMode accessMode() {
        return accessMode;
    }

    public boolean actionAuth() {
        return actionAuth;
    }

    public boolean dataAuth() {
        return dataAuth;
    }

    public ActionDefaultGrantPolicy defaultGrantPolicy() {
        return defaultGrantPolicy;
    }

    public String permissionActionCode() {
        return permissionAction == null ? code : permissionAction.code();
    }

    public String inheritActionCode() {
        return permissionAction == null ? null : permissionAction.code();
    }

    public ActionExecutionPolicy executionPolicy() {
        return ActionExecutionPolicy.standard(this);
    }

    public boolean matches(String actionCode) {
        return code.equals(actionCode);
    }

    public static Optional<PlatformAction> fromCode(String actionCode) {
        return Arrays.stream(values())
                .filter(action -> action.matches(actionCode))
                .findFirst();
    }

    public static String permissionActionCodeOf(String actionCode) {
        if (actionCode == null || actionCode.isBlank()) {
            throw new IllegalArgumentException("actionCode must not be blank");
        }
        String validActionCode = actionCode.trim();
        return fromCode(validActionCode).map(PlatformAction::permissionActionCode).orElse(validActionCode);
    }

    public static List<PlatformAction> ofGroup(PlatformActionGroup group) {
        return Arrays.stream(values())
                .filter(action -> action.group == group)
                .sorted(Comparator.comparingInt(PlatformAction::order))
                .toList();
    }
}
