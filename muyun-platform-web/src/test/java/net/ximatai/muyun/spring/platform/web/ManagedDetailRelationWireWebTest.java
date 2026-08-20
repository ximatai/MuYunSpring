package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.web.WebPageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ManagedDetailRelationWireWebTest {
    private static final long PRECISE_LONG = 9_007_199_254_740_993L;
    private static final BigDecimal PRECISE_DECIMAL = new BigDecimal("0.123456789012345678");

    @Test
    void shouldApplyCompleteChildWireFactsToQueryInsertAndUpdateResponses() throws Exception {
        FieldUiControlService parentService = mock(FieldUiControlService.class);
        ManagedDetailRelationGateway gateway = mock(ManagedDetailRelationGateway.class);
        PreciseChild child = preciseChild("child-1");
        org.mockito.Mockito.doReturn(new WebPageResponse<>(List.of(child), 1, 1, 20, 1, true, null))
                .when(gateway).query(eq(FieldUiControlService.MODULE_ALIAS), eq(parentService), eq("select"),
                        eq("properties"), any());
        when(gateway.insert(eq(FieldUiControlService.MODULE_ALIAS), eq(parentService), eq("select"),
                eq("properties"), any(Map.class))).thenReturn(child);
        when(gateway.update(eq(FieldUiControlService.MODULE_ALIAS), eq(parentService), eq("select"),
                eq("properties"), eq("child-1"), any(Map.class))).thenReturn(child);

        ModuleExecutionPlanCatalog catalog = mock(ModuleExecutionPlanCatalog.class);
        ModuleExecutionPlan plan = mock(ModuleExecutionPlan.class);
        when(catalog.find(FieldUiControlService.MODULE_ALIAS)).thenReturn(Optional.of(plan));
        when(plan.detailRelationWireFieldTypes()).thenReturn(Map.of("properties", Map.of(
                "hiddenLong", FieldValueType.LONG,
                "hiddenDecimal", FieldValueType.DECIMAL)));
        StandardModuleWebRuntime runtime = new StandardModuleWebRuntime(catalog,
                mock(StaticRecordReadProjectionService.class));
        FieldUiControlWebController controller = new FieldUiControlWebController();
        ReflectionTestUtils.setField(controller, "service", parentService);
        controller.setManagedDetailRelationGateway(gateway);
        controller.setStandardModuleWebRuntime(runtime);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new StandardModuleWireResponseAdvice(new ObjectMapper()))
                .build();
        String base = "/platform.field_ui_control/view/select/relations/properties";

        mvc.perform(post(base + "/query").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].hiddenLong").value("9007199254740993"))
                .andExpect(jsonPath("$.records[0].hiddenDecimal").value("0.123456789012345678"));
        mvc.perform(post(base + "/insert").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hiddenLong").value("9007199254740993"))
                .andExpect(jsonPath("$.hiddenDecimal").value("0.123456789012345678"));
        mvc.perform(post(base + "/update/child-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hiddenLong").value("9007199254740993"))
                .andExpect(jsonPath("$.hiddenDecimal").value("0.123456789012345678"));
    }

    private static PreciseChild preciseChild(String id) {
        PreciseChild child = new PreciseChild();
        child.setId(id);
        child.setVersion(1);
        child.setHiddenLong(PRECISE_LONG);
        child.setHiddenDecimal(PRECISE_DECIMAL);
        return child;
    }

    public static final class PreciseChild extends StandardEntity {
        private Long hiddenLong;
        private BigDecimal hiddenDecimal;

        public Long getHiddenLong() {
            return hiddenLong;
        }

        public void setHiddenLong(Long hiddenLong) {
            this.hiddenLong = hiddenLong;
        }

        public BigDecimal getHiddenDecimal() {
            return hiddenDecimal;
        }

        public void setHiddenDecimal(BigDecimal hiddenDecimal) {
            this.hiddenDecimal = hiddenDecimal;
        }
    }
}
