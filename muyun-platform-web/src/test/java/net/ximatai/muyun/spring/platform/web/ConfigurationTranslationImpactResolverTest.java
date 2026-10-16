package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.event.*;
import net.ximatai.muyun.spring.ability.action.*;
import net.ximatai.muyun.spring.ability.query.*;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.dictionary.*;
import net.ximatai.muyun.spring.platform.localization.ConfigurationTextSources;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.web.realtime.DataChangeRealtimePublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConfigurationTranslationImpactResolverTest {
    @Test void notifiesOnlyInstalledConsumersWithoutRebuildingPlansOrSendingOriginalFacts() {
        var items = mock(DictionaryItemService.class); var plans = mock(ModuleExecutionPlanCatalog.class);
        var item = new DictionaryItem(); item.setId("item"); item.setCategoryId("category");
        var category = new DictionaryCategory(); category.setApplicationAlias("sales"); category.setAlias("state");
        when(items.select("item")).thenReturn(item); when(items.category("category")).thenReturn(category);
        var consumer = plan("sales.order", OptionBinding.dictionary("sales", "state"));
        var unrelated = plan("sales.other", OptionBinding.dictionary("sales", "other"));
        when(plans.installedPlans()).thenReturn(Map.of("sales.order", consumer, "sales.other", unrelated));
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("items", items); beans.registerSingleton("plans", plans);
        var impacts = new ConfigurationTranslationImpactResolver(beans.getBeanProvider(DictionaryItemService.class), beans.getBeanProvider(ModuleExecutionPlanCatalog.class));
        var publisher = mock(DataChangeRealtimePublisher.class);
        var source = ConfigurationTextSources.dictionaryTitle("item", "已到货");
        var event = RuntimeEvent.of(RuntimeEventType.CONFIG_TRANSLATION_PUBLISHED, DictionaryItemService.MODULE_ALIAS,
                null, null, null, null, true, "published", RuntimeMutationSource.SYSTEM,
                Map.of("sourceId", source.sourceId(), "sourceVersion", source.sourceVersion()));
        try (var scope = TenantContext.system("translation publication")) {
            new ConfigurationTranslationRealtimeBridge(publisher, impacts).onRuntimeEvent(event);
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
        var changeSet = ArgumentCaptor.forClass(CommittedChangeSet.class);
        verify(publisher).publish(changeSet.capture());
        assertThat(changeSet.getValue().changes()).extracting(DataChange::moduleAlias).containsExactlyInAnyOrder("platform.dictionary_item", "sales.order");
        assertThat(changeSet.getValue().changes()).allSatisfy(change -> {
            assertThat(change.type()).isEqualTo(DataChangeTypes.CONFIGURATION_TRANSLATIONS_CHANGED);
            assertThat(change.recordId()).isNull();
            assertThat(change.facts()).isEmpty();
        });
        verify(plans, never()).rebuildDynamicPlan(anyString());
        assertThat(consumer.querySchema().fields().getFirst().optionBinding()).isEqualTo(OptionBinding.dictionary("sales", "state"));
    }
    private ModuleExecutionPlan plan(String alias, OptionBinding binding) {
        var descriptor = QueryDescriptor.builder(alias).field(QueryField.of("state", QueryValueType.STRING, QueryOperator.EQ).withOptionBinding(binding)).build();
        var ui = new ResolvedModuleUiDescriptor("", alias, ModuleKind.STATIC, "Module", List.of(), null, List.of(), null, null, List.of(), List.of(), List.of());
        return new ModuleExecutionPlan(alias, "revision", ui, new ResolvedModuleReadModel(alias, "record", List.of()), List.of(), descriptor,
                QuerySchema.from(descriptor), List.of(), List.of(), false);
    }
}
