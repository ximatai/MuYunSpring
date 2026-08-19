package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionAvailability;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.web.PlatformModuleRuntimeActionWebController;
import net.ximatai.muyun.spring.platform.web.DynamicRelationProjectionReadService;
import net.ximatai.muyun.spring.platform.web.PlatformRecordActionAvailability;
import net.ximatai.muyun.spring.platform.web.PlatformRecordActionAvailabilityService;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.TenantRequestScope;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserProvider;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        DynamicRecordWebController.class,
        PlatformModuleRuntimeActionWebController.class
})
@Import({
        DynamicRecordWebMvcSliceTest.StaticContractController.class,
        CurrentUserWebFilter.class,
        DynamicRecordJacksonConfiguration.class,
        DynamicRelationProjectionReadService.class,
        DynamicRecordWebServiceConfiguration.class
})
class DynamicRecordWebMvcSliceTest {
    private static final String MODULE = "sales.contract";
    private static final String ENTITY = "contract";

    private final MockMvc mvc;

    @MockitoBean
    private DynamicRecordService recordService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private ActiveTenantVerifier activeTenantVerifier;

    @MockitoBean
    private TenantRequestScope tenantRequestScope;

    @MockitoBean
    private PlatformRecordActionAvailabilityService recordActionAvailabilityService;

    @Autowired
    DynamicRecordWebMvcSliceTest(MockMvc mvc) {
        this.mvc = mvc;
    }

    @BeforeEach
    void setUpCurrentUser() {
        when(currentUserProvider.currentUser())
                .thenReturn(Optional.of(CurrentUser.tenantUser("user-1", "User", "tenant_a")));
        when(recordService.actionAuthorizationAvailability(eq(MODULE), anyString(), any()))
                .thenAnswer(invocation -> DynamicActionAvailability.available(invocation.getArgument(1)));
        when(recordService.actionAuthorizationAvailability(eq(MODULE), eq(ENTITY), anyString(), any()))
                .thenAnswer(invocation -> DynamicActionAvailability.available(invocation.getArgument(2)));
    }

    @Test
    void shouldLetStaticControllerTakeOverExactAliasPathAndKeepDynamicFallback() throws Exception {
        mvc.perform(post("/{moduleAlias}/query", MODULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("static"));
        verifyNoInteractions(recordService);

        DynamicRecord record = new DynamicRecord(entity()).setValue("code", "C-001");
        record.setId("contract-1");
        DynamicEntityOperations mainEntity = mock(DynamicEntityOperations.class);
        when(recordService.mainEntity(MODULE)).thenReturn(mainEntity);
        when(mainEntity.select("contract-1")).thenReturn(record);

        mvc.perform(get("/{moduleAlias}/view/{recordId}", MODULE, "contract-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("contract-1"))
                .andExpect(jsonPath("$.values.code").value("C-001"));
    }

    @Test
    void shouldNotCaptureRootFileLikePathInRealMvcMapping() throws Exception {
        mvc.perform(get("/openapi.json"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(recordService);
    }

    @Test
    void shouldRouteRecordActionAvailabilityThroughPlatformRuntimeActionController() throws Exception {
        when(recordActionAvailabilityService.recordActions(MODULE, "contract-1"))
                .thenReturn(new PlatformRecordActionAvailability(
                        "contract-1",
                        List.of(new PlatformRecordActionAvailability.Action("submit", true, null))
                ));

        mvc.perform(get("/{moduleAlias}/actions/{recordId}", MODULE, "contract-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordId").value("contract-1"))
                .andExpect(jsonPath("$.actions[0].actionCode").value("submit"))
                .andExpect(jsonPath("$.actions[0].available").value(true));

        verifyNoInteractions(recordService);
    }

    @Test
    void shouldRejectPostForReadOnlyDynamicEndpointsInRealMvcMapping() throws Exception {
        mvc.perform(post("/{moduleAlias}/view/{recordId}", MODULE, "contract-1"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/{moduleAlias}/actions/{recordId}", MODULE, "contract-1"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/{moduleAlias}/describe", MODULE))
                .andExpect(status().isMethodNotAllowed());
    }

    private EntityDefinition entity() {
        return new EntityDefinition(ENTITY, "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code").length(64).required()
        ));
    }

    private DynamicActionDescriptor action(String code, EntityActionLevel level) {
        return new DynamicActionDescriptor(code, "Submit", true, level, EntityActionCategory.CUSTOM,
                EntityActionAccessMode.AUTH_REQUIRED, true, false, null, false, null,
                EntityActionExecutorType.SERVICE, "submitExecutor");
    }

    @RestController
    @RequestMapping("/sales.contract")
    static class StaticContractController {
        @PostMapping("/query")
        Map<String, String> query() {
            return Map.of("source", "static");
        }
    }
}
