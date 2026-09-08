package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.ability.action.FormActionResult;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FormActionWebTest {
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
    public static class FormController extends ExistingController implements FormActionWeb<Object, Map<String,Object>, Object> {
        @Override public Object executeFormAction(String code, FormActionRequest<Map<String,Object>> request) {
            return new FormActionResult<>(request.record(), "calculated");
        }
    }
}
