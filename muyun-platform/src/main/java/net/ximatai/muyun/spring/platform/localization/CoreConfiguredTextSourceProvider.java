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
import java.util.Objects;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;

/** Privileged maintenance reads original objects, including immutable published workflow versions. */
@Service
public class CoreConfiguredTextSourceProvider implements ConfiguredTextSourceProvider {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private final ObjectProvider<MenuService> menus;
    private final ObjectProvider<DictionaryItemService> dictionaries;
    private final ObjectProvider<WorkflowDefinitionService> definitions;
    private final ObjectProvider<WorkflowVersionDao> versions;
    private final ObjectMapper mapper;
    private final ObjectProvider<PlatformModuleService> modules;
    public CoreConfiguredTextSourceProvider(ObjectProvider<MenuService> menus, ObjectProvider<DictionaryItemService> dictionaries,
            ObjectProvider<WorkflowDefinitionService> definitions, ObjectProvider<WorkflowVersionDao> versions, ObjectMapper mapper, ObjectProvider<PlatformModuleService> modules) {
        this.menus = Objects.requireNonNull(menus);
        this.dictionaries = Objects.requireNonNull(dictionaries);
        this.definitions = Objects.requireNonNull(definitions);
        this.versions = Objects.requireNonNull(versions);
        this.mapper = Objects.requireNonNull(mapper);
        this.modules = Objects.requireNonNull(modules);
    }
    @Override public List<TextReference> currentSources(String moduleAlias) {
        if (!TenantContext.isSystem()) throw new IllegalStateException("Configuration source maintenance requires system scope");
        List<TextReference> result = new ArrayList<>();
        var moduleService = modules.getIfAvailable();
        if (moduleService != null) {
            var module = moduleService.select(moduleAlias);
            if (module != null && module.titleSource() != null && module.titleSource().sourceKind() == TextReference.SourceKind.CONFIG)
                result.add(module.titleSource());
        }
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
                if (!ids.isEmpty()) for (var version : versionDao.query(Criteria.of().in("definitionId", ids).in("publishStatus", List.of(WorkflowPublishStatus.PUBLISHED, WorkflowPublishStatus.RETIRED)), ALL))
                    result.addAll(workflowSources(version));
            }
        }
        return List.copyOf(result);
    }
    private List<TextReference> workflowSources(WorkflowVersion version) {
        try {
            var snapshot = mapper.readValue(version.getSnapshotText(), WorkflowDesignDocument.class);
            var result = new ArrayList<TextReference>();
            for (var node : snapshot.nodes()) {
                if (node.getTitle() != null && !node.getTitle().isBlank())
                    result.add(ConfigurationTextSources.workflowNodeTitle(version.getId(), node.getNodeKey(), node.getTitle()));
                if (node.getNodeConfigText() == null || node.getNodeConfigText().isBlank()) continue;
                var task = mapper.readTree(node.getNodeConfigText()).path("task");
                if (task.isMissingNode()) continue;
                var specification = mapper.treeToValue(task, WorkflowBusinessTaskSpec.class);
                for (var guide : specification.guides()) {
                    if (guide.getTitle() != null && !guide.getTitle().isBlank())
                        result.add(ConfigurationTextSources.workflowGuideText(version.getId(), node.getNodeKey(), guide.getGuideKey(), "title", guide.getTitle()));
                    if (guide.getGuideConfigText() != null && !guide.getGuideConfigText().isBlank()) {
                        var instruction = mapper.readTree(guide.getGuideConfigText()).path("instruction");
                        if (instruction.isTextual() && !instruction.asText().isBlank())
                            result.add(ConfigurationTextSources.workflowGuideText(version.getId(), node.getNodeKey(), guide.getGuideKey(), "instruction", instruction.asText()));
                    }
                }
            }
            return List.copyOf(result);
        } catch (Exception failure) { throw new IllegalStateException("Cannot read published workflow text sources: " + version.getId(), failure); }
    }
}
