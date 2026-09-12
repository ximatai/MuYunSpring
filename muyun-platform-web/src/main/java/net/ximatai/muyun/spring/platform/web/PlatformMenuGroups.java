package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.menu.MenuService;

public final class PlatformMenuGroups {
    public static final String PLATFORM = MenuService.ADMIN_PLATFORM_GROUP_ID;
    public static final String MODELING = MenuService.ADMIN_MODELING_GROUP_ID;
    public static final String CONFIG = MODELING;
    public static final String IDENTITY = MenuService.ADMIN_IDENTITY_GROUP_ID;
    public static final String BUSINESS_SUPPORT = MenuService.ADMIN_BUSINESS_SUPPORT_GROUP_ID;
    public static final String LOG_MANAGEMENT = MenuService.ADMIN_LOG_MANAGEMENT_GROUP_ID;
    public static final String OPS = MenuService.ADMIN_OPS_GROUP_ID;
    public static final String SETTINGS = MenuService.ADMIN_SETTINGS_GROUP_ID;

    private PlatformMenuGroups() {
    }
}
