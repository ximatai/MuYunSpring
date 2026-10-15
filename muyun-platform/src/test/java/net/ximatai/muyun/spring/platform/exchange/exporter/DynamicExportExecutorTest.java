package net.ximatai.muyun.spring.platform.exchange.exporter;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordActionGateway;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelSheetPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPlan;
import net.ximatai.muyun.spring.platform.exchange.template.DynamicExchangeTemplatePlanBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import java.util.Optional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicExportExecutorTest {
    private static final String MODULE = "sales.order";

    private final DynamicRecordService recordService = mock(DynamicRecordService.class);
    private final DynamicRecordActionGateway records = mock(DynamicRecordActionGateway.class);
    private final DynamicExportExecutor executor = new DynamicExportExecutor(
            recordService, new DynamicExchangeTemplatePlanBuilder());

    @BeforeEach
    void configureReferenceTargets() {
        PlatformAbilityRuntime.configureReferenceTargetResolver(target -> {
            ReferenceAbility<StandardTitledEntity> ability = mock(ReferenceAbility.class);
            when(ability.titles(any())).thenAnswer(invocation -> recordService.titles(
                    target.moduleAlias(), target.entityAlias(), invocation.getArgument(0)));
            return Optional.of(ability);
        });
    }

    @AfterEach
    void resetReferenceTargets() {
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void shouldExportMainRecordsThroughExportActionGateway() {
        EntityDefinition entity = new EntityDefinition("order", "sales_order", "Order", List.of(
                FieldDefinition.string("orderNo", "Order No").column("order_no"),
                FieldDefinition.string("status", "Status")
        ));
        DynamicModuleDescriptor descriptor = DynamicModuleDescriptor.from(new ModuleDefinition(
                MODULE,
                "Order",
                List.of(entity)
        ));
        Criteria criteria = Criteria.of().eq("status", "active");
        PageRequest pageRequest = PageRequest.of(1, 20);
        Sort sort = Sort.asc("orderNo");
        DynamicRecord first = new DynamicRecord(entity);
        first.setId("order-1");
        first.setValue("orderNo", "SO-001");
        first.setValue("status", "active");
        when(recordService.recordsForAction(MODULE, PlatformAction.EXPORT, "dynamic-export")).thenReturn(records);
        when(records.list("order", criteria, pageRequest, sort)).thenReturn(List.of(first));

        ExcelWorkbookPlan plan = executor.export(new DynamicExportCommand(
                descriptor, criteria, pageRequest, List.of(sort)));

        verify(recordService).recordsForAction(MODULE, PlatformAction.EXPORT, "dynamic-export");
        ExcelSheetPlan main = plan.sheets().getFirst();
        assertThat(main.rows()).containsExactly(List.of("order-1", "SO-001", "active"));
    }

    @Test
    void shouldKeepSelectedCriteriaOnExportActionGateway() {
        EntityDefinition entity = new EntityDefinition("order", "sales_order", "Order", List.of(
                FieldDefinition.string("orderNo", "Order No").column("order_no")
        ));
        DynamicModuleDescriptor descriptor = DynamicModuleDescriptor.from(new ModuleDefinition(
                MODULE,
                "Order",
                List.of(entity)
        ));
        Criteria selectedCriteria = Criteria.of().in("id", List.of("order-1", "order-2"));
        PageRequest pageRequest = PageRequest.of(1, 20);
        when(recordService.recordsForAction(MODULE, PlatformAction.EXPORT, "dynamic-export")).thenReturn(records);
        when(records.list("order", selectedCriteria, pageRequest)).thenReturn(List.of());

        executor.export(new DynamicExportCommand(descriptor, selectedCriteria, pageRequest, List.of()));

        verify(recordService).recordsForAction(MODULE, PlatformAction.EXPORT, "dynamic-export");
        verify(records).list("order", selectedCriteria, pageRequest);
    }

    @Test
    void shouldExportFirstLevelChildRowsWithParentRelateId() {
        EntityDefinition order = new EntityDefinition("order", "sales_order", "Order", List.of(
                FieldDefinition.string("orderNo", "Order No").column("order_no")
        ));
        EntityDefinition line = new EntityDefinition("order_line", "sales_order_line", "Order Line", List.of(
                FieldDefinition.string("orderId", "Order").column("order_id"),
                FieldDefinition.string("sku", "SKU")
        ));
        DynamicModuleDescriptor descriptor = DynamicModuleDescriptor.from(ModuleDefinition.builder(MODULE, "Order")
                .entities(List.of(order, line))
                .relations(List.of(EntityRelationDefinition.child("lines", "order", "order_line", "orderId")))
                .build());
        DynamicRecord first = new DynamicRecord(order);
        first.setId("order-1");
        first.setValue("orderNo", "SO-001");
        DynamicRecord second = new DynamicRecord(order);
        second.setId("order-2");
        second.setValue("orderNo", "SO-002");
        DynamicRecord firstLine = new DynamicRecord(line);
        firstLine.setId("line-1");
        firstLine.setValue("orderId", "order-1");
        firstLine.setValue("sku", "SKU-001");
        Criteria criteria = Criteria.of();
        PageRequest pageRequest = PageRequest.of(1, 50);
        when(recordService.recordsForAction(MODULE, PlatformAction.EXPORT, "dynamic-export")).thenReturn(records);
        when(records.list("order", criteria, pageRequest)).thenReturn(List.of(first, second));
        when(records.list(org.mockito.ArgumentMatchers.eq("order_line"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(firstLine));

        ExcelWorkbookPlan plan = executor.export(new DynamicExportCommand(
                descriptor, criteria, pageRequest, List.of()));

        assertThat(plan.sheets()).hasSize(2);
        ExcelSheetPlan main = plan.sheets().getFirst();
        assertThat(main.rows()).containsExactly(
                List.of("order-1", "SO-001"),
                List.of("order-2", "SO-002")
        );
        ExcelSheetPlan child = plan.sheets().get(1);
        assertThat(child.main()).isFalse();
        assertThat(child.entityAlias()).isEqualTo("order_line");
        assertThat(child.rows()).containsExactly(
                List.of("order-1", "order-1", "SKU-001"),
                Arrays.asList("order-2", null, null)
        );
    }

    @Test
    void shouldExportReferenceTitleInsteadOfId() {
        EntityDefinition order = new EntityDefinition("order", "sales_order", "Order", List.of(
                FieldDefinition.string("orderNo", "Order No").column("order_no"),
                FieldDefinition.string("customerId", "Customer").column("customer_id")
        ));
        EntityDefinition customer = new EntityDefinition("customer", "sales_customer", "Customer", List.of(
                FieldDefinition.string("name", "Name").title()
        ), Set.of(EntityCapability.REFERENCE));
        DynamicModuleDescriptor descriptor = DynamicModuleDescriptor.from(ModuleDefinition.builder(MODULE, "Order")
                .entities(List.of(order, customer))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("order", "customerId", "sales.order.customer")))
                .build());
        DynamicRecord first = new DynamicRecord(order);
        first.setId("order-1");
        first.setValue("orderNo", "SO-001");
        first.setValue("customerId", "customer-1");
        Criteria criteria = Criteria.of();
        PageRequest pageRequest = PageRequest.of(1, 20);
        when(recordService.recordsForAction(MODULE, PlatformAction.EXPORT, "dynamic-export")).thenReturn(records);
        when(records.list("order", criteria, pageRequest)).thenReturn(List.of(first));
        when(recordService.titles(MODULE, "customer", Set.of("customer-1")))
                .thenReturn(Map.of("customer-1", "Acme"));

        ExcelWorkbookPlan plan = executor.export(new DynamicExportCommand(
                descriptor, criteria, pageRequest, List.of()));

        ExcelSheetPlan main = plan.sheets().getFirst();
        assertThat(main.rows()).containsExactly(List.of("order-1", "SO-001", "Acme"));
    }

    @Test
    void shouldExportManyReferenceTitles() {
        EntityDefinition order = new EntityDefinition("order", "sales_order", "Order", List.of(
                FieldDefinition.string("orderNo", "Order No").column("order_no"),
                FieldDefinition.string("tagIds", "Tags").column("tag_ids")
        ));
        EntityDefinition tag = new EntityDefinition("tag", "sales_tag", "Tag", List.of(
                FieldDefinition.string("name", "Name").title()
        ), Set.of(EntityCapability.REFERENCE));
        DynamicModuleDescriptor descriptor = DynamicModuleDescriptor.from(ModuleDefinition.builder(MODULE, "Order")
                .entities(List.of(order, tag))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("order", "tagIds", "sales.order.tag").many()))
                .build());
        DynamicRecord first = new DynamicRecord(order);
        first.setId("order-1");
        first.setValue("orderNo", "SO-001");
        first.setValue("tagIds", "tag-1,tag-2");
        Criteria criteria = Criteria.of();
        PageRequest pageRequest = PageRequest.of(1, 20);
        when(recordService.recordsForAction(MODULE, PlatformAction.EXPORT, "dynamic-export")).thenReturn(records);
        when(records.list("order", criteria, pageRequest)).thenReturn(List.of(first));
        when(recordService.titles(MODULE, "tag", Set.of("tag-1", "tag-2")))
                .thenReturn(Map.of("tag-1", "Urgent", "tag-2", "Wholesale"));

        ExcelWorkbookPlan plan = executor.export(new DynamicExportCommand(
                descriptor, criteria, pageRequest, List.of()));

        ExcelSheetPlan main = plan.sheets().getFirst();
        assertThat(main.rows()).containsExactly(List.of("order-1", "SO-001", "Urgent,Wholesale"));
    }
    @Test
    void shouldBatchStaticTargetTitlesAndKeepMissingIdsWithoutUsingDynamicReads() {
        var entity = new EntityDefinition("order", "sales_order", "Order", List.of(
                FieldDefinition.string("role", "Role")));
        var descriptor = DynamicModuleDescriptor.from(ModuleDefinition.builder(MODULE, "Order")
                .entities(List.of(entity))
                .references(List.of(EntityReferenceDefinition.to("order", "role", "iam.role"))).build());
        ReferenceAbility<StandardTitledEntity> roles = mock(ReferenceAbility.class);
        PlatformAbilityRuntime.configureReferenceTargetResolver(target -> {
            assertThat(target).isEqualTo(ReferenceTarget.of("iam", "role"));
            return Optional.of(roles);
        });
        var first = new DynamicRecord(entity).setValue("role", "role-1"); first.setId("order-1");
        var second = new DynamicRecord(entity).setValue("role", "missing"); second.setId("order-2");
        when(recordService.recordsForAction(MODULE, PlatformAction.EXPORT, "dynamic-export")).thenReturn(records);
        when(records.list(org.mockito.ArgumentMatchers.eq("order"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(first, second));
        when(roles.titles(Set.of("role-1", "missing"))).thenReturn(Map.of("role-1", "租户管理员"));

        var plan = executor.export(new DynamicExportCommand(descriptor, Criteria.of(), PageRequest.of(1, 20), List.of()));

        assertThat(plan.sheets().getFirst().rows()).containsExactly(
                List.of("order-1", "租户管理员"), List.of("order-2", "missing"));
        verify(roles).titles(Set.of("role-1", "missing"));
        PlatformAbilityRuntime.configureReferenceTargetResolver(target -> Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.export(new DynamicExportCommand(
                descriptor, Criteria.of(), PageRequest.of(1, 20), List.of())))
                .isInstanceOfSatisfying(net.ximatai.muyun.spring.common.exception.PlatformException.class,
                        error -> assertThat(error.code()).isEqualTo(
                                net.ximatai.muyun.spring.common.exception.PlatformErrorCodes.CONFIG_MISSING));
        verify(recordService, org.mockito.Mockito.never()).titles(any(), any(), any());
    }

}
