package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.ability.action.FormActionResult;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FormActionWebTest {
    @Test
    void shouldFailClosedWhenRecordDataScopeHasNoServiceSupport() {
        Draft record = new Draft();
        record.setId("existing");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FormController().requireFormRecordScope(
                record, net.ximatai.muyun.spring.common.platform.PlatformAction.UPDATE.executionPolicy()))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
    }

    @Test
    void shouldUseThePersistedCrudRecordAsTheStaticFormScopeFallback() {
        @SuppressWarnings("unchecked")
        CrudAbility<Draft> service = mock(CrudAbility.class);
        CrudFormController controller = new CrudFormController(service);
        Draft record = new Draft();
        record.setId("existing");
        when(service.select("existing")).thenReturn(record);

        org.assertj.core.api.Assertions.assertThatCode(() -> controller.requireFormRecordScope(
                record, net.ximatai.muyun.spring.common.platform.PlatformAction.UPDATE.executionPolicy()))
                .doesNotThrowAnyException();

        when(service.select("existing")).thenReturn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.requireFormRecordScope(
                record, net.ximatai.muyun.spring.common.platform.PlatformAction.UPDATE.executionPolicy()))
                .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
    }

    @Test
    void existingListActionDoesNotBecomeAFormAction() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ExistingController()).build();
        try (var ignored = TenantContext.use("tenant-test")) {
            mvc.perform(post("/existing/form-actions/calculate").contentType("application/json").content("{}"))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void explicitStaticFormActionReceivesUnsavedChildrenAndReturnsTypedPatch() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FormController()).build();
        try (var ignored = TenantContext.use("tenant-test")) {
            mvc.perform(post("/fixture/form-actions/calculate").contentType("application/json")
                    .content("{\"record\":{\"title\":\"draft\",\"rows\":[{\"quantity\":3}]}}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recordPatch.title").value("draft"))
                    .andExpect(jsonPath("$.recordPatch.rows[0].quantity").value(3))
                    .andExpect(jsonPath("$.message").value("calculated"));
        }
    }

    @RestController
    @RequestMapping("/existing")
    public static class ExistingController implements ActionWeb<Object, Map<String,Object>, String, Object> {
        public Object service() { return this; }
        public List<String> listActions() { return List.of(); }
        public Object executeListAction(String code, Map<String,Object> request) { throw new AssertionError("Must not invoke list action"); }
        public Object executeBatchAction(String code, Map<String,Object> request) { throw new AssertionError(); }
        public Object executeRecordAction(String code, String id, Map<String,Object> request) { throw new AssertionError(); }
    }
    @RestController
    @RequestMapping("/fixture")
    public static class FormController extends ExistingController implements FormActionWeb<Object, Draft, Object> {
        @Override public Object executeFormAction(String code, FormActionRequest<Draft> request) {
            return new FormActionResult<>(request.record(), "calculated");
        }
    }

    public static class CrudFormController implements FormActionWeb<CrudAbility<Draft>, Draft, Object> {
        private final CrudAbility<Draft> service;
        CrudFormController(CrudAbility<Draft> service) { this.service = service; }
        @Override public CrudAbility<Draft> service() { return service; }
        @Override public Object executeFormAction(String code, FormActionRequest<Draft> request) { return null; }
    }

    public static class Draft extends net.ximatai.muyun.spring.common.model.standard.StandardEntity {
        private String title;
        private List<Map<String, Object>> rows;
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public List<Map<String, Object>> getRows() { return rows; }
        public void setRows(List<Map<String, Object>> rows) { this.rows = rows; }
    }
}
