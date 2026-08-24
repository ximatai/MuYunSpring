package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/** Validates the optional module-page menu header and exposes its server-side entry context. */
public class MenuEntryRequestInterceptor implements AsyncHandlerInterceptor {
    private final MenuService menuService;

    public MenuEntryRequestInterceptor(MenuService menuService) {
        this.menuService = menuService;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        String menuId = request.getHeader(MenuEntryRequestContext.HEADER_NAME);
        if (menuId == null || menuId.isBlank()) {
            return true;
        }
        Menu menu = menuService.currentUserVisibleMenu(menuId);
        if (menu == null || menu.getModuleAlias() == null || menu.getModuleAlias().isBlank()) {
            throw new PlatformException("Menu is not visible or is not a module entry: " + menuId);
        }
        MenuEntryRequestContext.bind(request, menu);
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                Exception ex) {
        MenuEntryRequestContext.clear(request);
    }

    @Override
    public void afterConcurrentHandlingStarted(@NonNull HttpServletRequest request,
                                               @NonNull HttpServletResponse response,
                                               @NonNull Object handler) {
        MenuEntryRequestContext.clear(request);
    }
}
