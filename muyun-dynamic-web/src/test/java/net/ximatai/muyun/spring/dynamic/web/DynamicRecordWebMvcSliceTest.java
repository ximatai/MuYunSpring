package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Test
    void shouldRoundTripStandardEditorPayloadTypesThroughDynamicRecordHttpWrites() throws Exception {
        DynamicEntityOperations mainEntity = mock(DynamicEntityOperations.class);
        DynamicRecord saved = new DynamicRecord(editorEntity())
                .setValue("quantity", 2)
                .setValue("amount", new BigDecimal("23.40"))
                .setValue("deliveryDate", "2026-08-21")
                .setValue("scheduledAt", "2026-08-21T03:00:00Z")
                .setValue("payload", Map.of("level", 3, "tags", List.of("vip")));
        saved.setId("contract-1");
        when(recordService.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(recordService.mainEntity(MODULE)).thenReturn(mainEntity);
        when(mainEntity.newRecord()).thenAnswer(invocation -> new DynamicRecord(editorEntity()));
        when(mainEntity.insert(any(DynamicRecord.class))).thenReturn("contract-1");
        when(mainEntity.select("contract-1")).thenReturn(saved);

        String payload = """
                {"quantity":2,"amount":23.40,"deliveryDate":"2026-08-21","scheduledAt":"2026-08-21T03:00:00Z",
                 "payload":{"level":3,"tags":["vip"]}}
                """;
        mvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.values.quantity").value(2))
                .andExpect(jsonPath("$.values.amount").value(23.4))
                .andExpect(jsonPath("$.values.deliveryDate").value("2026-08-21"))
                .andExpect(jsonPath("$.values.scheduledAt").value("2026-08-21T03:00:00Z"))
                .andExpect(jsonPath("$.values.payload.level").value(3));

        ArgumentCaptor<DynamicRecord> insert = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(mainEntity).insert(insert.capture());
        assertThat(insert.getValue().getValue("quantity")).isInstanceOf(Integer.class).isEqualTo(2);
        assertThat(insert.getValue().getValue("amount")).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) insert.getValue().getValue("amount")).isEqualByComparingTo("23.40");
        assertThat(insert.getValue().getValue("deliveryDate")).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(insert.getValue().getValue("scheduledAt")).isEqualTo(Instant.parse("2026-08-21T03:00:00Z"));
        assertThat(insert.getValue().getValue("payload")).isEqualTo(Map.of("level", 3, "tags", List.of("vip")));

        mvc.perform(post("/{moduleAlias}/update/{id}", MODULE, "contract-1")
                        .contentType("application/json")
                        .content("{\"quantity\":4,\"amount\":45.60,\"deliveryDate\":\"2026-08-22\",\"scheduledAt\":\"2026-08-22T04:05:00Z\","
                                + "\"payload\":[\"renewed\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.payload.level").value(3));

        ArgumentCaptor<DynamicRecord> update = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(mainEntity).update(update.capture());
        assertThat(update.getValue().getValue("quantity")).isEqualTo(4);
        assertThat((BigDecimal) update.getValue().getValue("amount")).isEqualByComparingTo("45.60");
        assertThat(update.getValue().getValue("deliveryDate")).isEqualTo(LocalDate.of(2026, 8, 22));
        assertThat(update.getValue().getValue("scheduledAt")).isEqualTo(Instant.parse("2026-08-22T04:05:00Z"));
        assertThat(update.getValue().getValue("payload")).isEqualTo(List.of("renewed"));
    }

    private EntityDefinition entity() {
        return new EntityDefinition(ENTITY, "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code").length(64).required()
        ));
    }

    private EntityDefinition editorEntity() {
        return new EntityDefinition(ENTITY, "sales_contract", "Contract", List.of(
                FieldDefinition.of("quantity", FieldType.INTEGER, "Quantity"),
                FieldDefinition.of("amount", FieldType.DECIMAL, "Amount"),
                FieldDefinition.of("deliveryDate", FieldType.DATE, "Delivery date").column("delivery_date"),
                FieldDefinition.of("scheduledAt", FieldType.TIMESTAMP, "Scheduled at").column("scheduled_at"),
                FieldDefinition.of("payload", FieldType.JSON, "Payload")
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
