package net.ximatai.muyun.spring.platform.localization;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.workflow.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

/** Privileged maintenance reads original objects, including immutable published workflow versions. */
@Service
public class CoreConfiguredTextSourceProvider implements ConfiguredTextSourceProvider {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private final ObjectProvider<MenuService> menus;
    private final ObjectProvider<DictionaryItemService> dictionaries;
    private final ObjectProvider<WorkflowDefinitionService> definitions;
    private final ObjectProvider<WorkflowVersionDao> versions;
    private final ObjectMapper mapper;
    public CoreConfiguredTextSourceProvider(ObjectProvider<MenuService> menus, ObjectProvider<DictionaryItemService> dictionaries,
            ObjectProvider<WorkflowDefinitionService> definitions, ObjectProvider<WorkflowVersionDao> versions, ObjectMapper mapper) {
        this.menus = menus; this.dictionaries = dictionaries; this.definitions = definitions; this.versions = versions; this.mapper = mapper;
    }
    @Override public List<TextReference> currentSources(String moduleAlias) {
        if (!TenantContext.isSystem()) throw new IllegalStateException("Configuration source maintenance requires system scope");
        List<TextReference> result = new ArrayList<>();
        try (var ignored = TenantContext.bypassTenantFilter("configuration original source maintenance")) {
            if (MenuService.MODULE_ALIAS.equals(moduleAlias)) {
                var service = menus.getIfAvailable();
                if (service != null) for (var menu : service.list(Criteria.of(), ALL))
                    result.add(ConfigurationTextSources.menuTitle(menu.getId(), menu.getTitle()));
            }
            if (DictionaryItemService.MODULE_ALIAS.equals(moduleAlias)) {
                var service = dictionaries.getIfAvailable();
                if (service != null) for (var item : service.list(Criteria.of(), ALL))
                    result.add(ConfigurationTextSources.dictionaryTitle(item.getId(), item.getTitle()));
            }
            var definitionService = definitions.getIfAvailable();
            var versionDao = versions.getIfAvailable();
            if (definitionService != null && versionDao != null) {
                var ids = definitionService.list(Criteria.of().eq("moduleAlias", moduleAlias), ALL).stream().map(WorkflowDefinition::getId).toList();
                if (!ids.isEmpty()) for (var version : versionDao.query(Criteria.of().in("definitionId", ids).eq("publishStatus", WorkflowPublishStatus.PUBLISHED), ALL))
                    result.addAll(workflowSources(version));
            }
        }
        return List.copyOf(result);
    }
    private List<TextReference> workflowSources(WorkflowVersion version) {
        try {
            var snapshot = mapper.readValue(version.getSnapshotText(), WorkflowDesignDocument.class);
            return snapshot.nodes().stream().filter(node -> node.getTitle() != null && !node.getTitle().isBlank())
                    .map(node -> ConfigurationTextSources.workflowNodeTitle(version.getId(), node.getNodeKey(), node.getTitle())).toList();
        } catch (Exception failure) { throw new IllegalStateException("Cannot read published workflow text sources: " + version.getId(), failure); }
    }
}
