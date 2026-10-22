package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Server entry-adapter contract; actual tenant/action/data authorization remains the service's job. */
class NamedReadEntryBoundaryTest {
    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldUseServerMenuParametersAndRejectUnknownViewForListAndDetail() throws Exception {
        MenuService menus = mock(MenuService.class);
        Menu menu = menu("history");
        when(menus.currentUserVisibleMenu("read.history")).thenReturn(menu);
        MockHttpServletRequest request = request("read.history");
        // Neither a URL view nor browser-supplied entry JSON can replace persisted menu facts.
        request.addParameter("view", "current");
        request.addParameter("entryParamsJson", "{\"view\":\"current\"}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        new MenuEntryRequestInterceptor(menus).preHandle(request, new MockHttpServletResponse(), new Object());
        ReadController controller = new ReadController();
        Criteria criteria = mock(Criteria.class);
        controller.applyMenuEntryQueryCriteria(criteria);
        verify(criteria).eq("status", "RETURNED");
        assertThat(controller.hasActiveMenuEntryPolicy()).isTrue();
        assertThatThrownBy(() -> controller.requireMenuEntryRecord(PlatformAction.VIEW, new Row("CURRENT")))
                .isInstanceOf(PlatformException.class).hasMessageContaining("outside entry");
        controller.requireMenuEntryRecord(PlatformAction.VIEW, new Row("RETURNED"));
        menu.setEntryParamsJson("{\"view\":\"unknown\"}");
        MenuEntryRequestContext.bind(request, menu);
        assertThatThrownBy(() -> controller.applyMenuEntryQueryCriteria(criteria))
                .isInstanceOf(PlatformException.class).hasMessageContaining("Unknown view");
        assertThatThrownBy(() -> controller.requireMenuEntryRecord(PlatformAction.VIEW, new Row("RETURNED")))
                .isInstanceOf(PlatformException.class).hasMessageContaining("Unknown view");
    }

    @Test
    void shouldRevalidateMenuVisibilityForEveryRequestAndLeaveDirectReadsOnModuleAuthorization() throws Exception {
        MenuService menus = mock(MenuService.class);
        when(menus.currentUserVisibleMenu("read.history")).thenReturn(menu("history")).thenReturn(null);
        MenuEntryRequestInterceptor interceptor = new MenuEntryRequestInterceptor(menus);
        MockHttpServletRequest first = request("read.history");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(first));
        interceptor.preHandle(first, new MockHttpServletResponse(), new Object());
        interceptor.afterCompletion(first, new MockHttpServletResponse(), new Object(), null);
        assertThat(MenuEntryRequestContext.current()).isEmpty();
        // A changed identity, tenant or grant must be resolved by MenuService again, without an entry cache.
        assertThatThrownBy(() -> interceptor.preHandle(request("read.history"), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(PlatformException.class).hasMessageContaining("not visible");
        verify(menus, times(2)).currentUserVisibleMenu("read.history");
        MockHttpServletRequest direct = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(direct));
        interceptor.preHandle(direct, new MockHttpServletResponse(), new Object());
        ReadController controller = new ReadController();
        Criteria criteria = mock(Criteria.class);
        controller.applyMenuEntryQueryCriteria(criteria);
        verifyNoInteractions(criteria);
        assertThat(controller.hasActiveMenuEntryPolicy()).isFalse();
        // Optional menu entry restrictions are not mandatory domain/tenant authorization. The ordinary
        // endpoint remains valid without this header and must enforce those independently in the service.
    }

    private static Menu menu(String view) {
        Menu menu = new Menu();
        menu.setId("read." + view);
        menu.setModuleAlias("example.device_use");
        menu.setEntryParamsJson("{\"view\":\"" + view + "\"}");
        return menu;
    }

    private static MockHttpServletRequest request(String menuId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(MenuEntryRequestContext.HEADER_NAME, menuId);
        return request;
    }

    static class Row extends StandardEntity {
        final String status;
        Row(String status) { this.status = status; }
    }

    static class ReadController implements CrudWeb<Row, CrudAbility<Row>> {
        @Override public CrudAbility<Row> service() { throw new UnsupportedOperationException(); }
        @Override public String webScopeName() { return "example.device_use"; }
        @Override public ModulePageEntryPolicy<Row> modulePageEntryPolicy() {
            return new ModulePageEntryPolicy<>() {
                @Override public boolean supports(MenuEntryRequestContext entry) {
                    return webScopeName().equals(entry.moduleAlias());
                }
                private String status(MenuEntryRequestContext entry) {
                    try {
                        String view = new ObjectMapper().readTree(entry.entryParamsJson()).path("view").asText();
                        if (!Set.of("current", "history").contains(view)) throw new IllegalArgumentException();
                        return "history".equals(view) ? "RETURNED" : "CURRENT";
                    } catch (Exception exception) { throw new PlatformException("Unknown view"); }
                }
                @Override public void appendQueryCriteria(MenuEntryRequestContext entry, Criteria criteria) {
                    criteria.eq("status", status(entry));
                }
                @Override public void requireRecord(MenuEntryRequestContext entry, PlatformAction action, Row record) {
                    if (!status(entry).equals(record.status)) throw new PlatformException("Record outside entry");
                }
            };
        }
    }
}
