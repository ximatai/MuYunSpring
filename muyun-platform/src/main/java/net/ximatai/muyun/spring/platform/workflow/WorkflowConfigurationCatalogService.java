package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplateService;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationRuleService;
import org.springframework.stereotype.Service;
import java.util.List;

/** Authoring references owned by the selected business module. Publication still validates every reference. */
@Service
public class WorkflowConfigurationCatalogService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private final WorkflowTaskDefinitionService tasks;
    private final PlatformQueryTemplateService queries;
    private final RecordGenerationRuleService generations;
    private final net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService records;
    private final net.ximatai.muyun.spring.platform.module.PlatformModuleService modules;
    public WorkflowConfigurationCatalogService(WorkflowTaskDefinitionService tasks, PlatformQueryTemplateService queries,
                                                RecordGenerationRuleService generations,
            net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService records,
            net.ximatai.muyun.spring.platform.module.PlatformModuleService modules) {
        this.tasks = tasks;
        this.queries = queries;
        this.generations = generations;this.records = records;this.modules = modules;
    }
    public Catalog forModule(String moduleAlias) {
        var taskChoices = tasks.list(Criteria.of().eq("moduleAlias", moduleAlias).eq("enabled", true), ALL).stream()
                .map(item -> new Choice(item.getId(), item.getTitle(), null)).toList();
        var queryChoices = queries.listPublishedByModule(moduleAlias).stream()
                .map(item -> new Choice(item.getId(), item.getTitle(), null)).toList();
        var generationChoices = generations.list(Criteria.of().eq("sourceModuleAlias", moduleAlias).eq("enabled", true), ALL).stream()
                .map(item -> new Choice(item.getId(), item.getTitle(), item.getTargetModuleAlias())).toList();
        var module = modules.resolveVisibleModule(moduleAlias);
        var associations = module != null && module.getModuleKind() == net.ximatai.muyun.spring.platform.module.ModuleKind.DYNAMIC ? records.associationViews(moduleAlias).stream()
                .filter(view -> view.queryable()).map(view -> {
                    var target = modules.resolveVisibleModule(view.targetModuleAlias());
                    String title = target == null || target.getTitle() == null ? view.code() : "关联" + target.getTitle() + " · " + view.code();
                    return new Choice(view.code(), title, view.targetModuleAlias());
                }).toList() : List.<Choice>of();
        return new Catalog(taskChoices, queryChoices, generationChoices, associations);
    }
    public record Choice(String id, String title, String targetModuleAlias) {}
    public record Catalog(List<Choice> tasks, List<Choice> queries, List<Choice> generations, List<Choice> associations) {}
}
