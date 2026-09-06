package net.ximatai.muyun.spring.platform.runtime;

import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRefreshResult;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.MetadataView;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewField;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataField;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldAffect;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldAffectService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldFilter;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldFilterService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFormulaRule;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFormulaRuleService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplateService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformDynamicRuntimeRefreshCoordinatorTest {
    private final TestMemoryDao<ModuleMetadataRelation> relationDao = new TestMemoryDao<>();
    private final TestMemoryDao<ModuleMetadataField> moduleFieldDao = new TestMemoryDao<>();
    private final TestMemoryDao<MetadataView> viewDao = new TestMemoryDao<>();
    private final ModuleMetadataRelationService relationService =
            new ModuleMetadataRelationService(relationDao, mock(PlatformModuleService.class), mock(MetadataService.class));
    private final ModuleMetadataFieldService moduleFieldService =
            new ModuleMetadataFieldService(moduleFieldDao, relationService, mock(MetadataService.class),
                    mock(MetadataFieldService.class));
    private final MetadataViewService viewService = new MetadataViewService(viewDao, relationService);
    private final PlatformDynamicRuntimeRefreshService refreshService = mock(PlatformDynamicRuntimeRefreshService.class);
    private final PlatformDynamicRuntimeRefreshCoordinator coordinator =
            new PlatformDynamicRuntimeRefreshCoordinator(refreshService, relationService, moduleFieldService, viewService);

    @Test
    void shouldRefreshDistinctModulesReferencingChangedMetadataField() {
        when(refreshService.refresh(anyString())).thenAnswer(invocation -> result(invocation.getArgument(0)));
        relationDao.insert(relation("rel-customer-main", "crm.customer", "metadata-customer"));
        relationDao.insert(relation("rel-customer-child", "crm.customer", "metadata-customer"));
        relationDao.insert(relation("rel-order-main", "crm.order", "metadata-customer"));
        relationDao.insert(relation("rel-invoice-main", "finance.invoice", "metadata-invoice"));
        MetadataField field = new MetadataField();
        field.setMetadataId("metadata-customer");

        List<DynamicModuleRefreshResult> results = coordinator.refreshByMetadataField(field);

        assertThat(results).hasSize(2);
        verify(refreshService, times(1)).refresh("crm.customer");
        verify(refreshService, times(1)).refresh("crm.order");
        verify(refreshService, never()).refresh("finance.invoice");
    }

    @Test
    void shouldRefreshAfterCommitWhenTransactionIsActive() {
        clearTransactionState();
        try {
            when(refreshService.refresh(anyString())).thenAnswer(invocation -> result(invocation.getArgument(0)));
            relationDao.insert(relation("rel-customer-main", "crm.customer", "metadata-customer"));
            MetadataField field = new MetadataField();
            field.setMetadataId("metadata-customer");
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);

            List<DynamicModuleRefreshResult> results = coordinator.refreshByMetadataField(field);

            assertThat(results).isEmpty();
            verify(refreshService, never()).refresh(anyString());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(refreshService).refresh("crm.customer");
        } finally {
            clearTransactionState();
        }
    }

    @Test
    void shouldRefreshModuleResolvedFromModuleFieldFormulaViewAndActionChanges() {
        when(refreshService.refresh(anyString())).thenAnswer(invocation -> result(invocation.getArgument(0)));
        relationDao.insert(relation("rel-customer-main", "crm.customer", "metadata-customer"));
        moduleFieldDao.insert(moduleField("module-field-name", "rel-customer-main"));
        viewDao.insert(view("view-list", "rel-customer-main"));

        coordinator.refreshByModuleField(moduleField("module-field-code", "rel-customer-main"));
        coordinator.refreshByFieldFilter(filter("module-field-name"));
        coordinator.refreshByFieldAffect(affect("module-field-name"));
        coordinator.refreshByFormulaRule(formulaRule("rel-customer-main"));
        coordinator.refreshByMetadataView(view("view-form", "rel-customer-main"));
        coordinator.refreshByMetadataViewField(viewField("view-list"));
        coordinator.refreshByModuleAction(action("crm.customer"));

        verify(refreshService, times(7)).refresh("crm.customer");
    }

    @Test
    void servicesShouldDelegateContractChangesToCoordinatorAfterChanged() {
        PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator =
                mock(PlatformDynamicRuntimeRefreshCoordinator.class);
        ModuleMetadataRelationService relationHook = new ModuleMetadataRelationService(
                new TestMemoryDao<>(), mock(PlatformModuleService.class), mock(MetadataService.class),
                Optional.of(refreshCoordinator));
        MetadataFieldService metadataFieldHook = new MetadataFieldService(
                new TestMemoryDao<>(), mock(MetadataService.class), mock(FieldSpecService.class),
                Optional.of(refreshCoordinator));
        ModuleMetadataFieldService moduleFieldHook = new ModuleMetadataFieldService(
                new TestMemoryDao<>(), mock(ModuleMetadataRelationService.class), mock(MetadataService.class),
                mock(MetadataFieldService.class), null, Optional.empty(), Optional.of(refreshCoordinator));
        ModuleMetadataFieldFilterService filterHook = new ModuleMetadataFieldFilterService(
                new TestMemoryDao<>(), mock(ModuleMetadataFieldService.class), Optional.of(refreshCoordinator));
        ModuleMetadataFieldAffectService affectHook = new ModuleMetadataFieldAffectService(
                new TestMemoryDao<>(), mock(ModuleMetadataFieldService.class), Optional.of(refreshCoordinator));
        ModuleMetadataFormulaRuleService formulaHook = new ModuleMetadataFormulaRuleService(
                new TestMemoryDao<>(), mock(ModuleMetadataRelationService.class), mock(MetadataFieldService.class),
                Optional.of(refreshCoordinator));
        MetadataViewService viewHook = new MetadataViewService(
                new TestMemoryDao<>(), mock(ModuleMetadataRelationService.class), Optional.of(refreshCoordinator));
        MetadataViewFieldService viewFieldHook = new MetadataViewFieldService(
                new TestMemoryDao<>(), mock(MetadataViewService.class), mock(MetadataFieldService.class),
                mock(ModuleMetadataRelationService.class), null, null, Optional.of(refreshCoordinator));
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModule dynamicModule = new PlatformModule();
        dynamicModule.setAlias("crm.customer");
        dynamicModule.setModuleKind(ModuleKind.DYNAMIC);
        when(moduleService.select("crm.customer")).thenReturn(dynamicModule);
        PlatformModuleActionService actionHook = new PlatformModuleActionService(
                new TestMemoryDao<>(), moduleService, Optional.of(refreshCoordinator));

        ModuleMetadataRelation relation = relation("rel-customer-main", "crm.customer", "metadata-customer");
        MetadataField metadataField = new MetadataField();
        ModuleMetadataField moduleField = moduleField("module-field-name", "rel-customer-main");
        ModuleMetadataFieldFilter filter = filter("module-field-name");
        ModuleMetadataFieldAffect affect = affect("module-field-name");
        ModuleMetadataFormulaRule formulaRule = formulaRule("rel-customer-main");
        MetadataView view = view("view-list", "rel-customer-main");
        MetadataViewField viewField = viewField("view-list");
        PlatformModuleAction action = action("crm.customer");

        relationHook.afterChanged(relation);
        metadataFieldHook.afterChanged(metadataField);
        moduleFieldHook.afterChanged(moduleField);
        filterHook.afterChanged(filter);
        affectHook.afterChanged(affect);
        formulaHook.afterChanged(formulaRule);
        viewHook.afterChanged(view);
        viewFieldHook.afterChanged(viewField);
        actionHook.afterChanged(action);

        verify(refreshCoordinator).refreshByRelation(relation);
        verify(refreshCoordinator).refreshByMetadataField(metadataField);
        verify(refreshCoordinator).refreshByModuleField(moduleField);
        verify(refreshCoordinator).refreshByFieldFilter(filter);
        verify(refreshCoordinator).refreshByFieldAffect(affect);
        verify(refreshCoordinator).refreshByFormulaRule(formulaRule);
        verify(refreshCoordinator).refreshByMetadataView(view);
        verify(refreshCoordinator).refreshByMetadataViewField(viewField);
        verify(refreshCoordinator).refreshConfiguredModule(action.getModuleAlias());
    }

    @Test
    void staticModuleActionChangeShouldNotTriggerDynamicRuntimeRefresh() {
        PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator =
                mock(PlatformDynamicRuntimeRefreshCoordinator.class);
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModule staticModule = new PlatformModule();
        staticModule.setAlias("platform.code_issue_log");
        staticModule.setModuleKind(ModuleKind.STATIC);
        when(moduleService.select("platform.code_issue_log")).thenReturn(staticModule);
        PlatformModuleActionService actionHook = new PlatformModuleActionService(
                new TestMemoryDao<>(), moduleService, Optional.of(refreshCoordinator));

        PlatformModuleAction action = action("platform.code_issue_log");
        actionHook.afterChanged(action);

        verify(refreshCoordinator, never()).refreshConfiguredModule(action.getModuleAlias());
    }

    @Test
    void uiAndQuerySaveServicesShouldNotDependOnRuntimeRefreshCoordinator() {
        assertThat(hasCoordinatorDependency(PlatformUiConfigService.class)).isFalse();
        assertThat(hasCoordinatorDependency(PlatformQueryTemplateService.class)).isFalse();
    }

    private boolean hasCoordinatorDependency(Class<?> serviceClass) {
        for (Constructor<?> constructor : serviceClass.getDeclaredConstructors()) {
            for (Type parameterType : constructor.getGenericParameterTypes()) {
                if (isCoordinatorType(parameterType)) {
                    return true;
                }
            }
        }
        for (Field field : serviceClass.getDeclaredFields()) {
            if (isCoordinatorType(field.getGenericType())) {
                return true;
            }
        }
        return false;
    }

    private boolean isCoordinatorType(Type type) {
        if (type == PlatformDynamicRuntimeRefreshCoordinator.class) {
            return true;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                if (isCoordinatorType(argument)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ModuleMetadataRelation relation(String id, String moduleAlias, String metadataId) {
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setId(id);
        relation.setModuleAlias(moduleAlias);
        relation.setMetadataId(metadataId);
        relation.setRelationAlias("main");
        return relation;
    }

    private ModuleMetadataField moduleField(String id, String relationId) {
        ModuleMetadataField moduleField = new ModuleMetadataField();
        moduleField.setId(id);
        moduleField.setRelationId(relationId);
        moduleField.setMetadataFieldId("metadata-field-name");
        return moduleField;
    }

    private ModuleMetadataFieldFilter filter(String moduleFieldId) {
        ModuleMetadataFieldFilter filter = new ModuleMetadataFieldFilter();
        filter.setModuleMetadataFieldId(moduleFieldId);
        return filter;
    }

    private ModuleMetadataFieldAffect affect(String moduleFieldId) {
        ModuleMetadataFieldAffect affect = new ModuleMetadataFieldAffect();
        affect.setModuleMetadataFieldId(moduleFieldId);
        return affect;
    }

    private ModuleMetadataFormulaRule formulaRule(String relationId) {
        ModuleMetadataFormulaRule rule = new ModuleMetadataFormulaRule();
        rule.setRelationId(relationId);
        return rule;
    }

    private MetadataView view(String id, String relationId) {
        MetadataView view = new MetadataView();
        view.setId(id);
        view.setRelationId(relationId);
        return view;
    }

    private MetadataViewField viewField(String viewId) {
        MetadataViewField viewField = new MetadataViewField();
        viewField.setViewId(viewId);
        return viewField;
    }

    private PlatformModuleAction action(String moduleAlias) {
        PlatformModuleAction action = new PlatformModuleAction();
        action.setModuleAlias(moduleAlias);
        return action;
    }

    private DynamicModuleRefreshResult result(String moduleAlias) {
        return new DynamicModuleRefreshResult(new ModuleDefinition(moduleAlias, moduleAlias, List.of()), Map.of(), false);
    }

    private void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}
