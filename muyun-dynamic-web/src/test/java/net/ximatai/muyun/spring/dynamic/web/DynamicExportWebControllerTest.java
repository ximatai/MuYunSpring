package net.ximatai.muyun.spring.dynamic.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.query.QueryValueType;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.exchange.exporter.DynamicExportCommand;
import net.ximatai.muyun.spring.platform.exchange.exporter.DynamicExportFacade;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryGroupOperator;
import net.ximatai.muyun.spring.platform.web.ModuleExecutionPlan;
import net.ximatai.muyun.spring.platform.web.ModuleExecutionPlanCatalog;
import net.ximatai.muyun.spring.platform.web.ModuleQueryFormField;
import net.ximatai.muyun.spring.platform.web.ModuleQueryTemplatePlan;
import net.ximatai.muyun.spring.platform.web.PageContextBindingDefinition;
import net.ximatai.muyun.spring.platform.web.ResolvedModuleReadModel;
import net.ximatai.muyun.spring.platform.web.ResolvedModuleUiDescriptor;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DynamicExportWebControllerTest {
    private static final String MODULE = "sales.order";
    private final ObjectMapper json = new ObjectMapper();
    private DynamicRecordService records;
    private DynamicExportFacade exports;
    private ModuleExecutionPlanCatalog plans;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        records = mock(DynamicRecordService.class);
        exports = mock(DynamicExportFacade.class);
        plans = mock(ModuleExecutionPlanCatalog.class);
        when(plans.find(MODULE)).thenReturn(Optional.of(plan(List.of())));
        when(records.describe(MODULE)).thenReturn(descriptor(true));
        DynamicEntityOperations operations = mock(DynamicEntityOperations.class);
        when(records.mainEntity(MODULE)).thenReturn(operations);
        when(operations.newRecord()).thenReturn(new DynamicRecord(entity()));
        when(operations.queryCriteria(anyList())).thenAnswer(invocation -> {
            List<net.ximatai.muyun.spring.dynamic.runtime.DynamicQueryCondition> conditions = invocation.getArgument(0);
            Criteria criteria = Criteria.of();
            conditions.forEach(condition -> criteria.eq(condition.fieldName(), condition.values().getFirst()));
            return criteria;
        });
        when(exports.exportWorkbook(any())).thenReturn(new byte[]{7, 8, 9});
        mvc = MockMvcBuilders.standaloneSetup(new DynamicExportWebController(
                        records, mock(ActiveTenantVerifier.class), exports, plans))
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
    }

    @Test
    void shouldPreserveExplicitPageAndPhysicalSort() throws Exception {
        mvc.perform(post("/{module}/export/data", MODULE).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("uiConfigId", "ui-list",
                                "page", Map.of("pageNum", 2, "pageSize", 50),
                                "conditions", List.of(Map.of("fieldName", "status", "operator", "EQ", "values", List.of("active"))),
                                "sorts", List.of(Map.of("field", "orderNo", "desc", true))))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-FileName", "sales_order-export.xlsx"))
                .andExpect(content().bytes(new byte[]{7, 8, 9}));
        var command = command();
        assertThat(command.pageRequest().getOffset()).isEqualTo(50);
        assertThat(command.pageRequest().getLimit()).isEqualTo(50);
        assertThat(command.sorts()).hasSize(1);
        assertClause(command.criteria(), "status", CriteriaOperator.EQ, "active");
    }

    @Test
    void shouldExportAllMatchingRowsUsingCompiledTemplateFormSearchAndScope() throws Exception {
        when(plans.find(MODULE)).thenReturn(Optional.of(plan(List.of(
                PageContextBindingDefinition.navigator("owner", net.ximatai.muyun.spring.platform.web.PageContextTarget.LIST_QUERY, "ownerId")))));
        mvc.perform(post("/{module}/export/data", MODULE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uiConfigId":"ui-list","unpaged":true,"page":{"pageNum":2,"pageSize":20},
                                 "queryTemplateId":"tpl-active","externalQueryValues":{"ownerId":"user-1","status":"active"},
                                 "queryForm":{"orderNo":"A"},"quickSearch":"A_1","quickSearchFields":["orderNo"],
                                 "criteria":{"kind":"GROUP","operator":"OR","children":[
                                   {"kind":"CONDITION","fieldName":"status","operator":"EQ","values":["pending"]}]}}
                                """))
                .andExpect(status().isOk());
        var command = command();
        assertThat(command.pageRequest().getOffset()).isZero();
        assertThat(command.pageRequest().getLimit()).isEqualTo(Integer.MAX_VALUE);
        assertClause(command.criteria(), "ownerId", CriteriaOperator.EQ, "user-1");
        assertClause(command.criteria(), "status", CriteriaOperator.EQ, "active");
        assertClause(command.criteria(), "status", CriteriaOperator.EQ, "pending");
        assertClause(command.criteria(), "orderNo", CriteriaOperator.EQ, "A");
        assertClause(command.criteria(), "orderNo", CriteriaOperator.LIKE_IGNORE_CASE, "%A\\_1%");
    }

    @Test
    void shouldIntersectSelectedIdsWithTheSamePublishedScope() throws Exception {
        when(plans.find(MODULE)).thenReturn(Optional.of(plan(List.of(
                PageContextBindingDefinition.navigator("owner", net.ximatai.muyun.spring.platform.web.PageContextTarget.LIST_QUERY, "ownerId")))));
        mvc.perform(post("/{module}/export/selected", MODULE).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ids":["order-1","order-2","order-1"],
                                 "query":{"uiConfigId":"ui-list","unpaged":true,"page":{"pageNum":2,"pageSize":20},
                                 "sorts":[{"field":"orderNo","desc":true}],"externalQueryValues":{"ownerId":"user-1"}}}
                                """))
                .andExpect(status().isOk());
        var command = command();
        assertClause(command.criteria(), "ownerId", CriteriaOperator.EQ, "user-1");
        assertThat(command.pageRequest().getOffset()).isZero();
        assertThat(command.pageRequest().getLimit()).isEqualTo(Integer.MAX_VALUE);
        assertThat(command.sorts()).hasSize(1);
        assertThat(clauses(command.criteria())).anySatisfy(clause -> {
            assertThat(clause.getField()).isEqualTo("id");
            assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.IN);
            assertThat(clause.getValues()).containsExactly("order-1", "order-2");
        });
    }

    @Test
    void shouldRejectStaleOrMissingListRevision() throws Exception {
        mvc.perform(post("/{module}/export/data", MODULE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"uiConfigId\":\"ui-old\"}")).andExpect(status().isConflict());
        mvc.perform(post("/{module}/export/data", MODULE)).andExpect(status().isBadRequest());
        verify(exports, never()).exportWorkbook(any());
    }

    @Test
    void shouldRejectMissingRequiredScopeBeforeExporting() throws Exception {
        when(plans.find(MODULE)).thenReturn(Optional.of(plan(List.of(
                PageContextBindingDefinition.navigator("owner", net.ximatai.muyun.spring.platform.web.PageContextTarget.LIST_QUERY, "ownerId")))));
        mvc.perform(post("/{module}/export/data", MODULE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"uiConfigId\":\"ui-list\",\"unpaged\":true}")).andExpect(status().isBadRequest());
        verify(exports, never()).exportWorkbook(any());
    }

    @Test
    void shouldRejectUnsupportedQueryAndExportFields() throws Exception {
        for (String extra : List.of("\"sorts\":[{\"field\":\"displayCode\",\"desc\":true}]",
                "\"quickSearch\":\"A\",\"quickSearchFields\":[\"displayCode\"]",
                "\"conditions\":[{\"fieldName\":\"hidden\",\"operator\":\"EQ\",\"values\":[\"A\"]}]",
                "\"queryTemplateId\":\"unpublished\"", "\"queryForm\":{\"hidden\":\"A\"}")) {
            mvc.perform(post("/{module}/export/data", MODULE).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"uiConfigId\":\"ui-list\"," + extra + "}")).andExpect(status().isBadRequest());
        }
        verify(exports, never()).exportWorkbook(any());
    }

    @Test
    void shouldRejectMissingExchangeCapabilityAndEmptySelection() throws Exception {
        mvc.perform(post("/{module}/export/selected", MODULE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[]}")).andExpect(status().isBadRequest());
        when(records.describe(MODULE)).thenReturn(descriptor(false));
        mvc.perform(post("/{module}/export/data", MODULE)).andExpect(status().isBadRequest());
        verify(exports, never()).exportWorkbook(any());
    }

    private DynamicExportCommand command() {
        var captor = ArgumentCaptor.forClass(DynamicExportCommand.class);
        verify(exports).exportWorkbook(captor.capture());
        return captor.getValue();
    }

    private ModuleExecutionPlan plan(List<PageContextBindingDefinition> bindings) {
        var ui = new ResolvedModuleUiDescriptor(null, MODULE, ModuleKind.DYNAMIC, "Order", List.of(),
                "orderNo", List.of(), null, null, List.of(), List.of(), List.of());
        var schema = new QuerySchema(MODULE, "order", new QuerySchema.QuickSearch(true, List.of("orderNo"), List.of()),
                List.of("orderNo", "status", "ownerId", "displayCode").stream().map(field -> new QuerySchema.Field(
                        field, field, QueryValueType.STRING, List.of(QueryOperator.EQ), QueryOperator.EQ,
                        true, true, null, null, null)).toList(), List.of(), List.of());
        return new ModuleExecutionPlan(MODULE, "published-1", ui,
                new ResolvedModuleReadModel(MODULE, "order", List.of()), bindings,
                QueryDescriptor.builder(MODULE).build(), schema, List.of("tpl-active"),
                List.of(new ModuleQueryTemplatePlan("tpl-active", List.of(new ModuleQueryTemplatePlan.Node(
                        PlatformQueryGroupOperator.AND, "status", DynamicQueryOperator.EQ, "active", "status", null, List.of())))),
                "ui-list", null, List.of(new ModuleQueryFormField("orderNo", null, List.of())),
                List.of(), List.of(), List.of(), false);
    }

    private EntityDefinition entity() {
        return new EntityDefinition("order", "sales_order", "Order", List.of(
                FieldDefinition.string("orderNo", "Order No").column("order_no"),
                FieldDefinition.string("status", "Status"), FieldDefinition.string("ownerId", "Owner").column("owner_id"),
                FieldDefinition.string("displayCode", "Display Code").column("display_code").virtual()));
    }

    private DynamicModuleDescriptor descriptor(boolean exchange) {
        return DynamicModuleDescriptor.from(new ModuleDefinition(MODULE, "Order", List.of(
                exchange ? entity().withCapabilities(EntityCapability.EXCHANGE) : entity())));
    }

    private void assertClause(Criteria criteria, String field, CriteriaOperator operator, Object value) {
        assertThat(clauses(criteria)).anySatisfy(clause -> {
            assertThat(clause.getField()).isEqualTo(field);
            assertThat(clause.getOperator()).isEqualTo(operator);
            assertThat(clause.getValues()).contains(value);
        });
    }

    private List<CriteriaClause> clauses(Criteria criteria) {
        List<CriteriaClause> result = new ArrayList<>();
        collect(criteria.getRoot(), result);
        return result;
    }

    private void collect(CriteriaGroup group, List<CriteriaClause> result) {
        for (CriteriaGroup.Entry entry : group.getEntries()) {
            var node = entry.getNode();
            if (node instanceof CriteriaClause clause) result.add(clause);
            else if (node instanceof CriteriaGroup child) collect(child, result);
        }
    }
}
