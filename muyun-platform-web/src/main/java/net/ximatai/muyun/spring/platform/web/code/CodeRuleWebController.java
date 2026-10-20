package net.ximatai.muyun.spring.platform.web.code;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebScope;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.web.StaticQueryViewWeb;
import net.ximatai.muyun.spring.platform.web.StaticModuleWebControllerAdapter;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.code.CodeConfigurationService;
import org.springframework.web.bind.annotation.RequestParam;
import net.ximatai.muyun.spring.platform.code.CodePreviewResult;
import net.ximatai.muyun.spring.platform.code.CodePreviewService;
import net.ximatai.muyun.spring.platform.code.CodeRecycleEntry;
import net.ximatai.muyun.spring.platform.code.CodeRecycleStatus;
import net.ximatai.muyun.spring.platform.code.CodeLedgerEntry;
import net.ximatai.muyun.spring.platform.code.CodeLedgerInspection;
import net.ximatai.muyun.spring.platform.code.CodeOpsActionService;
import net.ximatai.muyun.spring.platform.code.CodeOpsQueryService;
import net.ximatai.muyun.spring.platform.code.CodeRule;
import net.ximatai.muyun.spring.platform.code.CodeRuleOpsSnapshot;
import net.ximatai.muyun.spring.platform.code.CodeRuleService;
import net.ximatai.muyun.spring.platform.code.CodeSequenceBaselineResult;
import net.ximatai.muyun.spring.platform.code.CodeSequenceStateLocation;
import net.ximatai.muyun.spring.platform.code.PreviewCodeRuleCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = CodeRuleService.MODULE_ALIAS, title = "业务编码", route = "/platform/code-rule")
@PlatformMenu(parent = PlatformMenuGroups.BUSINESS_SUPPORT, order = 20)
@RequestMapping({"/platform.code_rule", "/platform/code/rule"})
public class CodeRuleWebController extends StaticModuleWebControllerAdapter<CodeRuleService> implements
        StaticQueryViewWeb<CodeRule, CodeRuleService>, StaticModuleUiContributor {

    private final CodePreviewService previewService;
    private final CodeConfigurationService configuration;
    private final net.ximatai.muyun.spring.platform.code.CodeBusinessTimeService time;
    private final CodeOpsQueryService opsQueryService;
    private final CodeOpsActionService opsActionService;

    public CodeRuleWebController(CodePreviewService previewService) {
        this(previewService, null, null);
    }

    public CodeRuleWebController(CodePreviewService previewService,
                                 CodeOpsQueryService opsQueryService,
                                 CodeOpsActionService opsActionService) {
        this(previewService, opsQueryService, opsActionService, null);
    }

    public CodeRuleWebController(CodePreviewService previewService, CodeOpsQueryService opsQueryService,
                                 CodeOpsActionService opsActionService, CodeConfigurationService configuration) {
        this(previewService, opsQueryService, opsActionService, configuration, null);
    }

    @Autowired
    public CodeRuleWebController(CodePreviewService previewService, CodeOpsQueryService opsQueryService,
                                 CodeOpsActionService opsActionService, CodeConfigurationService configuration,
                                 net.ximatai.muyun.spring.platform.code.CodeBusinessTimeService time) {
        this.time = time;
        this.configuration = configuration;
        this.previewService = previewService;
        this.opsQueryService = opsQueryService;
        this.opsActionService = opsActionService;
    }

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(CodeRuleService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("编码规则").titleField("title").secondaryField("fieldName"))
                        .detail(detail -> detail.editor(form -> form.title("编码规则")
                                .field("title", field -> field.label("规则名称").readOnly())
                                .field("moduleAlias", field -> field.label("所属模块").readOnly())
                                .field("entityAlias", field -> field.label("业务对象").readOnly())
                                .field("fieldName", field -> field.label("编码字段").readOnly())
                                .field("mode", field -> field.label("编号方式").readOnly())
                                .field("enabled", field -> field.label("启用状态").readOnly())))))
                .build();
    }

    private <T> T configurationScope(java.util.function.Supplier<T> action) {
        boolean systemActor = net.ximatai.muyun.spring.common.identity.CurrentUserContext.isSystem();
        if (systemActor && net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId().isEmpty()) return action.get();
        return webScope(action);
    }

    @GetMapping("/targets")
    @CustomActionEndpoint(value = "configurationQuery", title = "查询编码配置目标", level = PlatformActionLevel.LIST, dataAuth = false)
    public List<CodeConfigurationService.Target> targets(@RequestParam(required = false) String moduleAlias) {
        return configurationScope(() -> configuration.targets(moduleAlias));
    }

    @PostMapping("/binding")
    @CustomActionEndpoint(value = "configureBinding", title = "配置业务编码字段", level = PlatformActionLevel.ANY, dataAuth = false)
    public CodeConfigurationService.Target bind(@RequestBody CodeConfigurationService.BindingCommand command) {
        return configurationScope(() -> configuration.bind(command));
    }

    @GetMapping("/rules")
    @CustomActionEndpoint(value = "configurationQuery", title = "查询编码配置目标", level = PlatformActionLevel.LIST, dataAuth = false)
    public List<CodeRule> rules(@RequestParam String moduleAlias, @RequestParam String entityAlias) {
        return webScope(() -> service().selectRuleTreesByBusinessObject(moduleAlias, entityAlias));
    }

    @GetMapping("/viewTree/{id}")
    @CustomActionEndpoint(value = "viewTree", title = "查看规则树",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "id")
    public CodeRule viewTree(@PathVariable String id) {
        return webScope(() -> service().viewRuleTree(id));
    }

    @PostMapping("/saveTree")
    @CustomActionEndpoint(value = "saveTree", title = "保存规则树",
            level = PlatformActionLevel.ANY, dataAuth = false)
    public CodeRule saveTree(@RequestBody CodeRule rule) {
        return webScope(() -> service().saveRuleTree(rule));
    }

    @PostMapping("/preview")
    @CustomActionEndpoint(value = "preview", title = "预览编码",
            level = PlatformActionLevel.ANY, dataAuth = false)
    public CodePreviewResult preview(@RequestBody PreviewRequest request) {
        return webScope(() -> {
            PreviewRequest normalized = request == null ? PreviewRequest.empty() : request;
            CodeRule rule = normalized.rule();
            if (normalized.ruleId() != null && !normalized.ruleId().isBlank()) {
                rule = service().viewRuleTree(normalized.ruleId());
            }
            service().validateRuleDraft(rule);
            if (rule != null && rule.getMode() == net.ximatai.muyun.spring.platform.code.CodeMode.MANUAL)
                throw new net.ximatai.muyun.spring.common.exception.PlatformException("Manual coding has no automatic preview");
            CodePreviewResult result = previewService.previewDraft(new PreviewCodeRuleCommand(
                    rule,
                    configuration == null ? normalized.context() : configuration.previewContext(rule, normalized.context()),
                    normalized.organizationId(),
                    time == null ? normalized.at() : time.resolveBusinessLocalDateTime(normalized.organizationId(), normalized.at()),
                    normalized.sequenceValue()
            ));
            return configuration == null ? result : new CodePreviewResult(configuration.normalizeValue(rule, result.value()), result.segments());
        });
    }

    @PostMapping("/ops/view/{id}")
    @CustomActionEndpoint(value = "opsRecordQuery", title = "编码运维记录查询",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "id")
    public CodeRuleOpsSnapshot viewOpsSnapshot(@PathVariable String id,
                                               @RequestBody(required = false) OpsSnapshotRequest request) {
        return webScope(() -> requireOpsQueryService().viewRuleSnapshot(
                id,
                request == null ? null : request.limitPerCategory()
        ));
    }

    @PostMapping("/ops/queryByBizObject")
    @CustomActionEndpoint(value = "opsQuery", title = "编码运维查询",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public List<CodeRuleOpsSnapshot> queryOpsSnapshots(@RequestBody OpsSnapshotRequest request) {
        return webScope(() -> {
            OpsSnapshotRequest normalized = request == null ? OpsSnapshotRequest.empty() : request;
            return requireOpsQueryService().queryBusinessObjectSnapshots(
                    normalized.moduleAlias(),
                    normalized.entityAlias(),
                    normalized.limitPerCategory()
            );
        });
    }

    @PostMapping("/ops/sequenceState/locate")
    @CustomActionEndpoint(value = "opsQuery", title = "编码运维查询",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public CodeSequenceStateLocation locateSequenceState(@RequestBody SequenceBucketRequest request) {
        return webScope(() -> requireOpsQueryService().locateSequenceState(
                request.ruleId(),
                request.basisKey(),
                request.periodKey()
        ));
    }

    @PostMapping("/ops/sequenceState/baseline")
    @CustomActionEndpoint(value = "opsManage", title = "编码运维管理",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public CodeSequenceBaselineResult setSequenceBaseline(@RequestBody SequenceBaselineRequest request) {
        return webScope(() -> requireOpsActionService().setSequenceBaseline(
                request.ruleId(),
                request.basisKey(),
                request.periodKey(),
                request.currentValue(),
                request.reason()
        ));
    }

    @PostMapping("/ops/recycleEntry/{entryId}/adjust")
    @CustomActionEndpoint(value = "opsManage", title = "编码运维管理",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public CodeRecycleEntry adjustRecycleEntry(@PathVariable String entryId,
                                               @RequestBody RecycleAdjustRequest request) {
        return webScope(() -> requireOpsActionService().adjustRecycleEntry(
                entryId,
                request.status(),
                request.reason()
        ));
    }

    @PostMapping("/ops/ledgerEntry/{entryId}/inspect")
    @CustomActionEndpoint(value = "opsQuery", title = "编码运维查询",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public CodeLedgerInspection inspectLedgerEntry(@PathVariable String entryId) {
        return webScope(() -> requireOpsActionService().inspectLedgerEntry(entryId));
    }

    @PostMapping("/ops/ledgerEntry/{entryId}/release")
    @CustomActionEndpoint(value = "opsManage", title = "编码运维管理",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public CodeLedgerEntry releaseLedgerEntry(@PathVariable String entryId,
                                              @RequestBody(required = false) ReleaseLedgerRequest request) {
        return webScope(() -> requireOpsActionService().releaseStaleLedgerEntry(
                entryId,
                request == null ? null : request.reason()
        ));
    }

    private CodeOpsQueryService requireOpsQueryService() {
        if (opsQueryService == null) {
            throw new IllegalStateException("Code ops query service is not configured");
        }
        return opsQueryService;
    }

    private CodeOpsActionService requireOpsActionService() {
        if (opsActionService == null) {
            throw new IllegalStateException("Code ops action service is not configured");
        }
        return opsActionService;
    }

    public record PreviewRequest(
            String ruleId,
            CodeRule rule,
            Map<String, Object> context,
            String organizationId,
            LocalDateTime at,
            Long sequenceValue
    ) {
        static PreviewRequest empty() {
            return new PreviewRequest(null, null, Map.of(), null, null, null);
        }
    }

    public record OpsSnapshotRequest(String moduleAlias, String entityAlias, Integer limitPerCategory) {
        static OpsSnapshotRequest empty() {
            return new OpsSnapshotRequest(null, null, null);
        }
    }

    public record SequenceBucketRequest(String ruleId, String basisKey, String periodKey) {
    }

    public record SequenceBaselineRequest(String ruleId,
                                          String basisKey,
                                          String periodKey,
                                          Long currentValue,
                                          String reason) {
    }

    public record RecycleAdjustRequest(CodeRecycleStatus status, String reason) {
    }

    public record ReleaseLedgerRequest(String reason) {
    }
}
