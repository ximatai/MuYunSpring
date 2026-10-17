package net.ximatai.muyun.spring.platform.localization;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.workflow.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CoreConfiguredTextSourceProviderTest {
    @Test void keepsEachPublishedVersionAsAnIndependentOriginalForTranslationCorrections() {
        var definitions = mock(WorkflowDefinitionService.class);
        var versions = mock(WorkflowVersionDao.class);
        var definition = new WorkflowDefinition(); definition.setId("definition");
        when(definitions.list(any(net.ximatai.muyun.database.core.orm.Criteria.class), any(net.ximatai.muyun.database.core.orm.PageRequest.class))).thenReturn(List.of(definition));
        var first = version("published-1", "旧节点");
        var second = version("published-2", "新节点");
        first.setPublishStatus(WorkflowPublishStatus.RETIRED);
        var guide = new WorkflowTaskGuide(); guide.setGuideKey("read"); guide.setTitle("办理说明"); guide.setGuideKind(WorkflowTaskGuideKind.READ_INSTRUCTION);
        guide.setGuideConfigText("{\"instruction\":\"请核对数据\"}");
        var check = new WorkflowTaskCheck(); check.setCheckKey("ready"); check.setTitle("业务完成条件"); check.setCheckKind(WorkflowTaskCheckKind.FORMULA);
        check.setFailureMessage("业务数据尚未满足完成条件");
        var node = new WorkflowNodeDefinition(); node.setNodeKey("approval"); node.setTitle("旧节点"); node.setNodeType(WorkflowNodeType.TASK);
        try {
            var mapper = new ObjectMapper();
            node.setNodeConfigText(mapper.writeValueAsString(java.util.Map.of("task", new WorkflowBusinessTaskSpec(null, List.of(check), List.of(guide)))));
            first.setSnapshotText(mapper.writeValueAsString(new WorkflowDesignDocument(List.of(node), List.of(), null)));
        } catch (Exception failure) { throw new AssertionError(failure); }
        when(versions.query(any(net.ximatai.muyun.database.core.orm.Criteria.class), any(net.ximatai.muyun.database.core.orm.PageRequest.class))).thenReturn(List.of(first, second));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("definitions", definitions); beans.registerSingleton("versions", versions);
        var httpMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
                .addDeserializer(WorkflowNodeType.class, new com.fasterxml.jackson.databind.JsonDeserializer<WorkflowNodeType>() {
                    @Override public WorkflowNodeType deserialize(com.fasterxml.jackson.core.JsonParser parser,
                            com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
                        if ("task".equals(parser.getValueAsString())) return WorkflowNodeType.TASK;
                        throw context.weirdStringException(parser.getValueAsString(), WorkflowNodeType.class, "HTTP code required");
                    }
                }));
        var provider = new CoreConfiguredTextSourceProvider(beans.getBeanProvider(net.ximatai.muyun.spring.platform.menu.MenuService.class),
                beans.getBeanProvider(net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService.class),
                beans.getBeanProvider(WorkflowDefinitionService.class), beans.getBeanProvider(WorkflowVersionDao.class), httpMapper, beans.getBeanProvider(net.ximatai.muyun.spring.platform.module.PlatformModuleService.class));
        try (var scope = TenantContext.system("translation maintenance")) {
            assertThat(provider.currentSources("sales.order")).containsExactly(
                    ConfigurationTextSources.workflowNodeTitle("published-1", "approval", "旧节点"),
                    ConfigurationTextSources.workflowCheckText("published-1", "approval", "ready", "title", "业务完成条件"),
                    ConfigurationTextSources.workflowCheckText("published-1", "approval", "ready", "failureMessage", "业务数据尚未满足完成条件"),
                    ConfigurationTextSources.workflowGuideText("published-1", "approval", "read", "title", "办理说明"),
                    ConfigurationTextSources.workflowGuideText("published-1", "approval", "read", "instruction", "请核对数据"),
                    ConfigurationTextSources.workflowNodeTitle("published-2", "approval", "新节点"));
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
    }
    @Test void readsMenuAndDictionarySourcesByIdIncludingEscapedIdentities() {
        var menus = mock(net.ximatai.muyun.spring.platform.menu.MenuService.class);
        var dictionaries = mock(net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService.class);
        var menu = new net.ximatai.muyun.spring.platform.menu.Menu(); menu.setId("menu/~source"); menu.setTitle("菜单");
        var item = new net.ximatai.muyun.spring.platform.dictionary.DictionaryItem(); item.setId("item/~source"); item.setTitle("选项");
        when(menus.select(menu.getId())).thenReturn(menu); when(dictionaries.select(item.getId())).thenReturn(item);
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("menus", menus); beans.registerSingleton("dictionaries", dictionaries);
        var provider = new CoreConfiguredTextSourceProvider(beans.getBeanProvider(net.ximatai.muyun.spring.platform.menu.MenuService.class),
                beans.getBeanProvider(net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService.class),
                beans.getBeanProvider(WorkflowDefinitionService.class), beans.getBeanProvider(WorkflowVersionDao.class), new ObjectMapper(),
                beans.getBeanProvider(net.ximatai.muyun.spring.platform.module.PlatformModuleService.class));
        try (var scope = TenantContext.system("indexed source maintenance")) {
            var menuSource = ConfigurationTextSources.menuTitle(menu.getId(), menu.getTitle());
            var itemSource = ConfigurationTextSources.dictionaryTitle(item.getId(), item.getTitle());
            assertThat(provider.findCurrentSource("platform.menu", menuSource.sourceId())).contains(menuSource);
            assertThat(provider.findCurrentSource("platform.dictionary_item", itemSource.sourceId())).contains(itemSource);
            assertThat(provider.findCurrentSource("platform.menu", "config/menu/missing/title")).isEmpty();
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
        verify(menus).select(menu.getId()); verify(menus).select("missing"); verifyNoMoreInteractions(menus);
        verify(dictionaries).select(item.getId()); verifyNoMoreInteractions(dictionaries);
    }

    @Test void rejectsOverlappingOrNoncanonicalSourceSegmentsWithoutParsingFailures() {
        var beans = new DefaultListableBeanFactory();
        var provider = new CoreConfiguredTextSourceProvider(beans.getBeanProvider(net.ximatai.muyun.spring.platform.menu.MenuService.class),
                beans.getBeanProvider(net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService.class),
                beans.getBeanProvider(WorkflowDefinitionService.class), beans.getBeanProvider(WorkflowVersionDao.class), new ObjectMapper(),
                beans.getBeanProvider(net.ximatai.muyun.spring.platform.module.PlatformModuleService.class));
        try (var scope = TenantContext.system("invalid source maintenance")) {
            for (String id : List.of("config/menu/title", "config/menu//title", "config/menu/a~2/title", "config/menu/a/b/title"))
                assertThat(provider.findCurrentSource("platform.menu", id)).isEmpty();
            assertThat(provider.findCurrentSource("platform.dictionary_item", "config/dictionary/item/title")).isEmpty();
        }
    }

    @Test void defaultSingleLookupRejectsConflictingOriginalsBeforeChoosingAVersion() {
        ConfiguredTextSourceProvider provider = alias -> List.of(
                ConfigurationTextSources.menuTitle("menu", "旧菜单"), ConfigurationTextSources.menuTitle("menu", "新菜单"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.findCurrentSource("platform.menu", "config/menu/menu/title"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflicting configured text source");
    }

    private WorkflowVersion version(String id, String title) {
        var version = new WorkflowVersion(); version.setId(id); version.setPublishStatus(WorkflowPublishStatus.PUBLISHED);
        version.setSnapshotText("{\"nodes\":[{\"nodeKey\":\"approval\",\"title\":\"" + title + "\"}],\"links\":[]}");
        return version;
    }
}
