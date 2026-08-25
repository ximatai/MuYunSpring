package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.web.WebReferenceResolveMode;
import net.ximatai.muyun.spring.web.WebReferenceResolveRequest;
import net.ximatai.muyun.spring.web.WebReferenceSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaticReferenceResolveFacadeTest {
    @Test
    void shouldDeliverExplicitSelectionProjectionsWithoutWritingTheSourceRecord() {
        @SuppressWarnings("unchecked") CrudAbility<SelectionOrder> source = mock(CrudAbility.class);
        doReturn(SelectionOrder.class).when(source).modelClass();
        doReturn("sales.selection_order").when(source).getModuleAlias();
        @SuppressWarnings("unchecked") ReferenceAbility<Customer> target = mock(ReferenceAbility.class);
        doReturn(Customer.class).when(target).modelClass();
        doReturn("crm.customer").when(target).getModuleAlias();
        when(target.referenceOptions(any(), any(PageRequest.class)))
                .thenReturn(PageResult.of(List.of(new ReferenceOption("customer-1", "星云科技")), 1,
                        PageRequest.of(1, 20)));
        when(target.projections(List.of("customer-1"), List.of("status")))
                .thenReturn(Map.of("customer-1", Map.of("status", "ACTIVE")));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.selection_order", "订单")
                .modelClass(SelectionOrder.class).build();
        StaticReferenceResolveFacade facade = new StaticReferenceResolveFacade(
                new StaticModuleDefinitionCatalog(List.of(definition)), new StaticAbilityCatalog(List.of(source, target)));

        var response = facade.resolve("sales.selection_order", "customerId", new WebReferenceResolveRequest(
                WebReferenceResolveMode.QUERY, null, null, List.of(), List.of(), null,
                new net.ximatai.muyun.spring.web.WebPageRequest(1, 20), true, null, null, null, null, null));

        assertThat(response.options()).singleElement().satisfies(item ->
                assertThat(item.projections()).containsEntry("status", "ACTIVE"));
        verify(target).projections(List.of("customer-1"), List.of("status"));
    }

    @Test
    void shouldDeliverExplicitMultiHopSelectionProjection() {
        @SuppressWarnings("unchecked") CrudAbility<MultiHopSelectionOrder> source = mock(CrudAbility.class);
        doReturn(MultiHopSelectionOrder.class).when(source).modelClass();
        doReturn("sales.multi_hop_selection_order").when(source).getModuleAlias();
        @SuppressWarnings("unchecked") ReferenceAbility<SelectionCustomer> customer = mock(ReferenceAbility.class);
        doReturn(SelectionCustomer.class).when(customer).modelClass();
        doReturn("crm.customer").when(customer).getModuleAlias();
        when(customer.referenceOptions(any(), any(PageRequest.class)))
                .thenReturn(PageResult.of(List.of(new ReferenceOption("customer-1", "星云科技")), 1,
                        PageRequest.of(1, 20)));
        when(customer.projections(List.of("customer-1"), List.of("organizationId")))
                .thenReturn(Map.of("customer-1", Map.of("organizationId", "organization-1")));
        @SuppressWarnings("unchecked") ReferenceAbility<SelectionOrganization> organization = mock(ReferenceAbility.class);
        doReturn(SelectionOrganization.class).when(organization).modelClass();
        doReturn("crm.organization").when(organization).getModuleAlias();
        when(organization.projections(List.of("organization-1"), List.of("regionCode")))
                .thenReturn(Map.of("organization-1", Map.of("regionCode", "CN-31")));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.multi_hop_selection_order", "订单")
                .modelClass(MultiHopSelectionOrder.class).build();
        StaticReferenceResolveFacade facade = new StaticReferenceResolveFacade(
                new StaticModuleDefinitionCatalog(List.of(definition)),
                new StaticAbilityCatalog(List.of(source, customer, organization)));

        var response = facade.resolve("sales.multi_hop_selection_order", "customerId", new WebReferenceResolveRequest(
                WebReferenceResolveMode.QUERY, null, null, List.of(), List.of(), null,
                new net.ximatai.muyun.spring.web.WebPageRequest(1, 20), true, null, null, null, null, null));

        assertThat(response.options()).singleElement().satisfies(item ->
                assertThat(item.projections()).containsEntry("organizationId.regionCode", "CN-31"));
        verify(customer).projections(List.of("customer-1"), List.of("organizationId"));
        verify(organization).projections(List.of("organization-1"), List.of("regionCode"));
    }

    @Test
    void shouldKeepNullBusinessValuesInReferenceResolveFormContext() {
        Map<String, Object> formValues = new LinkedHashMap<>();
        formValues.put("mobile", null);
        formValues.put("employeeNo", "E-001");
        WebReferenceResolveRequest request = new WebReferenceResolveRequest(
                WebReferenceResolveMode.QUERY, null, null, List.of(), List.of(), null,
                null, true, formValues, null, null, null, null);

        assertThat(request.formValues()).containsEntry("mobile", null).containsEntry("employeeNo", "E-001");
    }

    @Test
    void shouldResolveStaticCandidatesAndTranslationsThroughReferenceAbility() {
        @SuppressWarnings("unchecked") CrudAbility<Order> source = mock(CrudAbility.class);
        doReturn(Order.class).when(source).modelClass();
        when(source.getModuleAlias()).thenReturn("sales.order");
        @SuppressWarnings("unchecked") ReferenceAbility<Customer> target = mock(ReferenceAbility.class);
        doReturn(Customer.class).when(target).modelClass();
        when(target.getModuleAlias()).thenReturn("crm.customer");
        when(target.referenceOptions(any(), any(PageRequest.class)))
                .thenReturn(PageResult.of(List.of(new ReferenceOption("customer-1", "星云科技")), 1,
                        PageRequest.of(1, 20)));

        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .modelClass(Order.class).build();
        StaticReferenceResolveFacade facade = new StaticReferenceResolveFacade(
                new StaticModuleDefinitionCatalog(List.of(definition)), new StaticAbilityCatalog(List.of(source, target)));

        var query = facade.resolve("sales.order", "customerId", new WebReferenceResolveRequest(
                WebReferenceResolveMode.QUERY, null, "星云", List.of(), List.of(), null,
                new net.ximatai.muyun.spring.web.WebPageRequest(1, 20), true, null, null, null, null, null));
        var translation = facade.resolve("sales.order", "customerId", new WebReferenceResolveRequest(
                WebReferenceResolveMode.TRANSLATE, null, null, List.of("customer-1"), List.of(), null,
                null, true, null, null, null, null, null));

        assertThat(query.options()).extracting(item -> item.title()).containsExactly("星云科技");
        assertThat(translation.results()).singleElement().extracting(result -> result.item().title())
                .isEqualTo("星云科技");
        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        ArgumentCaptor<PageRequest> page = ArgumentCaptor.forClass(PageRequest.class);
        verify(target, org.mockito.Mockito.times(2)).referenceOptions(criteria.capture(), page.capture());
        assertThat(criteria.getAllValues().getFirst().getClauses()).singleElement().satisfies(clause -> {
            assertThat(clause.getField()).isEqualTo("title");
            assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.LIKE);
            assertThat(clause.getValues()).containsExactly("星云");
        });
        assertThat(page.getAllValues().getFirst().getOffset()).isZero();
        assertThat(page.getAllValues().getFirst().getLimit()).isEqualTo(20);
        assertThat(criteria.getAllValues().get(1).getClauses()).singleElement().satisfies(clause -> {
            assertThat(clause.getField()).isEqualTo("id");
            assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.IN);
            assertThat(clause.getValues()).containsExactly("customer-1");
        });
    }

    @Test
    void shouldResolveADynamicReferenceTargetThroughTheScopedDynamicService() {
        @SuppressWarnings("unchecked") CrudAbility<Order> source = mock(CrudAbility.class);
        doReturn(Order.class).when(source).modelClass();
        when(source.getModuleAlias()).thenReturn("sales.order");
        DynamicRecordService dynamicRecords = mock(DynamicRecordService.class);
        when(dynamicRecords.mainEntityAlias("crm")).thenReturn("customer");
        when(dynamicRecords.referenceOptions(eq("crm"), eq("customer"), any(), any(PageRequest.class)))
                .thenReturn(PageResult.of(List.of(new ReferenceOption("customer-1", "星云科技")), 1,
                        PageRequest.of(1, 50)));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .modelClass(Order.class).build();
        StaticReferenceResolveFacade facade = new StaticReferenceResolveFacade(
                new StaticModuleDefinitionCatalog(List.of(definition)), new StaticAbilityCatalog(List.of(source)), dynamicRecords);

        var response = facade.resolve("sales.order", "customerId", new WebReferenceResolveRequest(
                WebReferenceResolveMode.QUERY, null, null, List.of(), List.of(), null,
                new net.ximatai.muyun.spring.web.WebPageRequest(1, 50), true, null, null, null, null, null));

        assertThat(response.options()).extracting(item -> item.title()).containsExactly("星云科技");
        verify(dynamicRecords).referenceOptions(eq("crm"), eq("customer"), any(), any(PageRequest.class));
    }

    @Test
    void shouldResolveStaticSourceToDynamicMultiHopSelectionProjectionThroughTheUnifiedTargetResolver() {
        @SuppressWarnings("unchecked") CrudAbility<MultiHopSelectionOrder> source = mock(CrudAbility.class);
        doReturn(MultiHopSelectionOrder.class).when(source).modelClass();
        doReturn("sales.multi_hop_selection_order").when(source).getModuleAlias();
        DynamicRecordService dynamicRecords = mock(DynamicRecordService.class);
        when(dynamicRecords.referenceOptions(eq("crm"), eq("customer"), any(), any(PageRequest.class)))
                .thenReturn(PageResult.of(List.of(new ReferenceOption("customer-1", "星云科技")), 1,
                        PageRequest.of(1, 20)));
        @SuppressWarnings("unchecked") ReferenceAbility<?> customer = mock(ReferenceAbility.class);
        when(customer.projections(List.of("customer-1"), List.of("organizationId")))
                .thenReturn(Map.of("customer-1", Map.of("organizationId", "organization-1")));
        @SuppressWarnings("unchecked") ReferenceAbility<?> organization = mock(ReferenceAbility.class);
        when(organization.projections(List.of("organization-1"), List.of("regionCode")))
                .thenReturn(Map.of("organization-1", Map.of("regionCode", "CN-31")));
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.configureReferenceTargetResolver(
                new net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver() {
                    @Override
                    public java.util.Optional<ReferenceAbility<?>> resolve(net.ximatai.muyun.spring.ability.reference.ReferenceTarget target) {
                        return switch (target.qualifiedName()) {
                            case "crm.customer" -> java.util.Optional.of(customer);
                            case "crm.organization" -> java.util.Optional.of(organization);
                            default -> java.util.Optional.empty();
                        };
                    }

                    @Override
                    public java.util.Optional<net.ximatai.muyun.spring.ability.reference.ReferencePlan> referencePlan(
                            net.ximatai.muyun.spring.ability.reference.ReferenceTarget target, String sourceField) {
                        return "crm.customer".equals(target.qualifiedName()) && "organizationId".equals(sourceField)
                                ? java.util.Optional.of(net.ximatai.muyun.spring.ability.reference.ReferencePlan.of(
                                        "organizationId", net.ximatai.muyun.spring.ability.reference.ReferenceTarget.of("crm", "organization"),
                                        net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE))
                                : java.util.Optional.empty();
                    }
                });
        try {
            StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.multi_hop_selection_order", "订单")
                    .modelClass(MultiHopSelectionOrder.class).build();
            StaticReferenceResolveFacade facade = new StaticReferenceResolveFacade(
                    new StaticModuleDefinitionCatalog(List.of(definition)), new StaticAbilityCatalog(List.of(source)), dynamicRecords);

            var response = facade.resolve("sales.multi_hop_selection_order", "customerId", new WebReferenceResolveRequest(
                    WebReferenceResolveMode.QUERY, null, null, List.of(), List.of(), null,
                    new net.ximatai.muyun.spring.web.WebPageRequest(1, 20), true, null, null, null, null, null));

            assertThat(response.options()).singleElement().satisfies(item ->
                    assertThat(item.projections()).containsEntry("organizationId.regionCode", "CN-31"));
        } finally {
            net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.resetReferenceTargetResolver();
        }
    }

    @Test
    void shouldResolveCandidatesInTheEditedRecordTenantEvenForASystemOperator() {
        @SuppressWarnings("unchecked") CrudAbility<Order> source = mock(CrudAbility.class);
        doReturn(Order.class).when(source).modelClass();
        doReturn("sales.order").when(source).getModuleAlias();
        Order order = new Order();
        order.setId("order-1");
        order.setTenantId("tenant-a");
        when(source.select("order-1")).thenReturn(order);
        @SuppressWarnings("unchecked") ReferenceAbility<Customer> target = mock(ReferenceAbility.class);
        doReturn(Customer.class).when(target).modelClass();
        doReturn("crm.customer").when(target).getModuleAlias();
        AtomicReference<String> observedTenant = new AtomicReference<>();
        when(target.referenceOptions(any(), any(PageRequest.class))).thenAnswer(ignored -> {
            observedTenant.set(TenantContext.currentTenantId().orElse(null));
            return PageResult.of(List.of(), 0, PageRequest.of(1, 20));
        });
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .modelClass(Order.class).build();
        StaticReferenceResolveFacade facade = new StaticReferenceResolveFacade(
                new StaticModuleDefinitionCatalog(List.of(definition)), new StaticAbilityCatalog(List.of(source, target)));

        try (TenantContext.Scope ignored = TenantContext.system("system operator reference picker")) {
            facade.resolve("sales.order", "customerId", new WebReferenceResolveRequest(
                    WebReferenceResolveMode.QUERY, null, null, List.of(), List.of(), null,
                    new net.ximatai.muyun.spring.web.WebPageRequest(1, 20), true,
                    Map.of("tenantId", "tenant-b"), new WebReferenceSource("order-1"), null, null, null, null));
        }

        assertThat(observedTenant).hasValue("tenant-a");
    }

    @Test
    void shouldReadPersistedReferenceSourceThroughTheReferenceDataScope() {
        @SuppressWarnings("unchecked") CrudAbility<Order> source = mock(CrudAbility.class,
                withSettings().extraInterfaces(DataScopeAbility.class));
        @SuppressWarnings("unchecked") DataScopeAbility<Order> scopedSource = (DataScopeAbility<Order>) source;
        doReturn(Order.class).when(source).modelClass();
        doReturn("sales.order").when(source).getModuleAlias();
        Order order = new Order();
        order.setId("order-1");
        order.setTenantId("tenant-a");
        when(scopedSource.selectForAction(PlatformAction.REFERENCE, "order-1")).thenReturn(order);
        @SuppressWarnings("unchecked") ReferenceAbility<Customer> target = mock(ReferenceAbility.class);
        doReturn(Customer.class).when(target).modelClass();
        doReturn("crm.customer").when(target).getModuleAlias();
        when(target.referenceOptions(any(), any(PageRequest.class)))
                .thenReturn(PageResult.of(List.of(), 0, PageRequest.of(1, 20)));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .modelClass(Order.class).build();
        StaticReferenceResolveFacade facade = new StaticReferenceResolveFacade(
                new StaticModuleDefinitionCatalog(List.of(definition)), new StaticAbilityCatalog(List.of(source, target)));

        facade.resolve("sales.order", "customerId", new WebReferenceResolveRequest(
                WebReferenceResolveMode.QUERY, null, null, List.of(), List.of(), null,
                new net.ximatai.muyun.spring.web.WebPageRequest(1, 20), true,
                Map.of(), new WebReferenceSource("order-1"), null, null, null, null));

        verify(scopedSource).selectForAction(PlatformAction.REFERENCE, "order-1");
        verify(source, never()).selectActiveRaw("order-1");
    }

    @Test
    void shouldResolveTreeCandidatesInTheEditedRecordTenant() {
        @SuppressWarnings("unchecked") CrudAbility<Order> source = mock(CrudAbility.class);
        doReturn(Order.class).when(source).modelClass();
        doReturn("sales.order").when(source).getModuleAlias();
        Order order = new Order();
        order.setId("order-1");
        order.setTenantId("tenant-a");
        when(source.select("order-1")).thenReturn(order);
        @SuppressWarnings("unchecked") ReferenceAbility<TreeCustomer> target = mock(ReferenceAbility.class,
                withSettings().extraInterfaces(TreeAbility.class));
        doReturn(TreeCustomer.class).when(target).modelClass();
        doReturn("crm.customer").when(target).getModuleAlias();
        TreeCustomer customer = new TreeCustomer();
        customer.setId("customer-1");
        customer.setTitle("星云科技");
        AtomicReference<String> observedTenant = new AtomicReference<>();
        when(target.list(any(), any(PageRequest.class))).thenAnswer(ignored -> {
            observedTenant.set(TenantContext.currentTenantId().orElse(null));
            return List.of(customer);
        });
        when(target.referenceTitle(customer)).thenReturn("星云科技");
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .modelClass(Order.class).build();
        StaticReferenceResolveFacade facade = new StaticReferenceResolveFacade(
                new StaticModuleDefinitionCatalog(List.of(definition)), new StaticAbilityCatalog(List.of(source, target)));

        try (TenantContext.Scope ignored = TenantContext.system("system operator reference picker")) {
            var response = facade.resolve("sales.order", "customerId", new WebReferenceResolveRequest(
                    WebReferenceResolveMode.TREE, null, null, List.of(), List.of(), null,
                    null, true, Map.of(), new WebReferenceSource("order-1"), null, null, null, null));
            assertThat(response.tree()).singleElement().extracting(node -> node.record().title())
                    .isEqualTo("星云科技");
        }

        assertThat(observedTenant).hasValue("tenant-a");
    }

    @Test
    void shouldApplyCandidateDependencyToQueryAndValueTranslation() {
        @SuppressWarnings("unchecked") ReferenceAbility<Customer> target = mock(ReferenceAbility.class);
        doReturn(Customer.class).when(target).modelClass();
        doReturn("crm.customer").when(target).getModuleAlias();
        when(target.referenceOptions(any(), any(PageRequest.class)))
                .thenReturn(PageResult.of(List.of(new ReferenceOption("department-a", "研发部")), 1,
                        PageRequest.of(1, 20)));
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .modelClass(DependentOrder.class).build();
        StaticReferenceResolveFacade facade = new StaticReferenceResolveFacade(
                new StaticModuleDefinitionCatalog(List.of(definition)), new StaticAbilityCatalog(List.of(target)));

        facade.resolve("sales.order", "departmentId", new WebReferenceResolveRequest(
                WebReferenceResolveMode.QUERY, null, null, List.of(), List.of(), null,
                new net.ximatai.muyun.spring.web.WebPageRequest(1, 20), true,
                Map.of("organizationId", "organization-a"), null, null, null, null));
        facade.resolve("sales.order", "departmentId", new WebReferenceResolveRequest(
                WebReferenceResolveMode.TRANSLATE, null, null, List.of("department-a"), List.of(), null,
                null, true, Map.of("organizationId", "organization-a"), null, null, null, null));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(target, org.mockito.Mockito.times(2)).referenceOptions(criteria.capture(), any(PageRequest.class));
        assertThat(criteria.getAllValues()).allSatisfy(value -> assertThat(value.getClauses())
                .anySatisfy(clause -> {
                    assertThat(clause.getField()).isEqualTo("organizationId");
                    assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.EQ);
                    assertThat(clause.getValues()).containsExactly("organization-a");
                }));
    }

    @Test
    void shouldReturnNoTreeCandidatesWhenARequiredDependencyIsMissing() {
        @SuppressWarnings("unchecked") ReferenceAbility<TreeCustomer> target = mock(ReferenceAbility.class,
                withSettings().extraInterfaces(TreeAbility.class));
        doReturn(TreeCustomer.class).when(target).modelClass();
        doReturn("crm.customer").when(target).getModuleAlias();
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .modelClass(DependentTreeOrder.class).build();
        StaticReferenceResolveFacade facade = new StaticReferenceResolveFacade(
                new StaticModuleDefinitionCatalog(List.of(definition)), new StaticAbilityCatalog(List.of(target)));

        var response = facade.resolve("sales.order", "departmentId", new WebReferenceResolveRequest(
                WebReferenceResolveMode.TREE, null, null, List.of(), List.of(), null,
                null, true, Map.of(), null, null, null, null));

        assertThat(response.tree()).isEmpty();
        verify(target).list(any(), any(PageRequest.class));
    }

    @Test
    void shouldNotTreatFormTenantAsReferenceScopeWithoutAnExplicitPersistedSource() {
        @SuppressWarnings("unchecked") ReferenceAbility<Customer> target = mock(ReferenceAbility.class);
        doReturn(Customer.class).when(target).modelClass();
        doReturn("crm.customer").when(target).getModuleAlias();
        AtomicReference<String> observedTenant = new AtomicReference<>();
        when(target.referenceOptions(any(), any(PageRequest.class))).thenAnswer(ignored -> {
            observedTenant.set(TenantContext.currentTenantId().orElse(null));
            return PageResult.of(List.of(), 0, PageRequest.of(1, 20));
        });
        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .modelClass(Order.class).build();
        StaticReferenceResolveFacade facade = new StaticReferenceResolveFacade(
                new StaticModuleDefinitionCatalog(List.of(definition)), new StaticAbilityCatalog(List.of(target)));

        try (TenantContext.Scope ignored = TenantContext.system("system operator reference picker")) {
            facade.resolve("sales.order", "customerId", new WebReferenceResolveRequest(
                    WebReferenceResolveMode.QUERY, null, null, List.of(), List.of(), null,
                    new net.ximatai.muyun.spring.web.WebPageRequest(1, 20), true,
                    Map.of("tenantId", "tenant-b"), null, null, null, null));
        }

        assertThat(observedTenant.get()).isNull();
    }

    @Test
    void shouldResolveAReferenceDeclaredByAnAggregateChildModel() {
        @SuppressWarnings("unchecked") ReferenceAbility<Customer> target = mock(ReferenceAbility.class);
        doReturn(Customer.class).when(target).modelClass();
        doReturn("crm.customer").when(target).getModuleAlias();
        when(target.referenceOptions(any(), any(PageRequest.class)))
                .thenReturn(PageResult.of(List.of(new ReferenceOption("customer-1", "星云科技")), 1,
                        PageRequest.of(1, 20)));

        StaticModuleDefinition definition = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .modelClass(Order.class)
                .entityModelClasses(Map.of("lines", OrderLine.class))
                .build();
        StaticReferenceResolveFacade facade = new StaticReferenceResolveFacade(
                new StaticModuleDefinitionCatalog(List.of(definition)), new StaticAbilityCatalog(List.of(target)));

        var response = facade.resolve("sales.order", "customerId", new WebReferenceResolveRequest(
                WebReferenceResolveMode.QUERY, null, null, List.of(), List.of(), null,
                new net.ximatai.muyun.spring.web.WebPageRequest(1, 20), true, null, null, null, null, null));

        assertThat(response.options()).extracting(item -> item.title()).containsExactly("星云科技");
    }

    private static final class Order extends StandardEntity {
        @ReferenceTo(moduleAlias = "crm", entityAlias = "customer")
        private String customerId;
    }

    private static final class SelectionOrder extends StandardEntity {
        @ReferenceTo(moduleAlias = "crm", entityAlias = "customer", selectionProjections = "status")
        private String customerId;
    }

    private static final class MultiHopSelectionOrder extends StandardEntity {
        @ReferenceTo(moduleAlias = "crm", entityAlias = "customer", selectionProjections = "organizationId.regionCode")
        private String customerId;
    }

    private static final class SelectionCustomer extends StandardTitledEntity {
        @ReferenceTo(moduleAlias = "crm", entityAlias = "organization")
        private String organizationId;
    }

    private static final class SelectionOrganization extends StandardTitledEntity {
        private String regionCode;
    }

    private static final class Customer extends StandardTitledEntity {
    }

    private static final class DependentOrder extends StandardEntity {
        @ReferenceTo(moduleAlias = "crm", entityAlias = "customer",
                candidateBindings = @net.ximatai.muyun.spring.ability.reference.ReferenceCandidateBinding(
                        sourceField = "organizationId", targetField = "organizationId"))
        private String departmentId;
    }

    private static final class DependentTreeOrder extends StandardEntity {
        @ReferenceTo(moduleAlias = "crm", entityAlias = "customer",
                candidateBindings = @net.ximatai.muyun.spring.ability.reference.ReferenceCandidateBinding(
                        sourceField = "organizationId", targetField = "organizationId"))
        private String departmentId;
    }

    private static final class TreeCustomer extends StandardTitledEntity implements TreeCapable {
        private String parentId = TreeAbility.ROOT_ID;
        private Integer sortOrder;

        @Override public String getParentId() { return parentId; }
        @Override public void setParentId(String parentId) { this.parentId = parentId; }
        @Override public Integer getSortOrder() { return sortOrder; }
        @Override public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    }

    private static final class OrderLine extends StandardEntity {
        @ReferenceTo(moduleAlias = "crm", entityAlias = "customer")
        private String customerId;
    }
}
