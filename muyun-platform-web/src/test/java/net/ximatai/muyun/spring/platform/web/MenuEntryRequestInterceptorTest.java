package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MenuEntryRequestInterceptorTest {
    @Test
    void shouldExposeOnlyCurrentUsersVisibleMenuEntry() throws Exception {
        MenuService menuService = mock(MenuService.class);
        Menu menu = new Menu();
        menu.setId("menu.system-user");
        menu.setModuleAlias("iam.user");
        menu.setEntryParamsJson("{\"entry\":\"system-user\"}");
        when(menuService.currentUserVisibleMenu("menu.system-user")).thenReturn(menu);
        MenuEntryRequestInterceptor interceptor = new MenuEntryRequestInterceptor(menuService);
        MockHttpServletRequest request = request("menu.system-user");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(MenuEntryRequestContext.current()).contains(new MenuEntryRequestContext(
                "menu.system-user", "iam.user", "{\"entry\":\"system-user\"}"));
        MenuEntryRequestContext.requireModuleAlias("iam.user");
        assertThatThrownBy(() -> MenuEntryRequestContext.requireModuleAlias("iam.role"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("module mismatch");

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        assertThat(MenuEntryRequestContext.current()).isEmpty();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldRejectInvisibleOrNonModuleMenu() {
        MenuService menuService = mock(MenuService.class);
        when(menuService.currentUserVisibleMenu("menu.hidden")).thenReturn(null);
        MenuEntryRequestInterceptor interceptor = new MenuEntryRequestInterceptor(menuService);

        assertThatThrownBy(() -> interceptor.preHandle(request("menu.hidden"), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not visible");
    }

    private MockHttpServletRequest request(String menuId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam.user/query");
        request.addHeader(MenuEntryRequestContext.HEADER_NAME, menuId);
        return request;
    }
}
