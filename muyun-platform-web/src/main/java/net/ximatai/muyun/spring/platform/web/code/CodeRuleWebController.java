package net.ximatai.muyun.spring.platform.web.code;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebScope;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.web.ReadOnlyWeb;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
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
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = CodeRuleService.MODULE_ALIAS, title = "编码规则")
@PlatformMenu(parent = PlatformMenuGroups.BUSINESS_SUPPORT, order = 20)
@RequestMapping({"/platform.code_rule", "/platform/code/rule"})
public class CodeRuleWebController extends WebSupport<CodeRuleService> implements
        ReadOnlyWeb<CodeRule, CodeRuleService> {

    private final CodePreviewService previewService;
    private final CodeOpsQueryService opsQueryService;
    private final CodeOpsActionService opsActionService;

    public CodeRuleWebController(CodePreviewService previewService) {
        this(previewService, null, null);
    }

    @Autowired
    public CodeRuleWebController(CodePreviewService previewService,
                                 CodeOpsQueryService opsQueryService,
                                 CodeOpsActionService opsActionService) {
        this.previewService = previewService;
        this.opsQueryService = opsQueryService;
        this.opsActionService = opsActionService;
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
            return previewService.previewDraft(new PreviewCodeRuleCommand(
                    rule,
                    normalized.context(),
                    normalized.organizationId(),
                    normalized.at(),
                    normalized.sequenceValue()
            ));
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

    @PostMapping("/ops/recycleEntry/{id}/adjust")
    @CustomActionEndpoint(value = "opsRecordManage", title = "编码运维记录管理",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "id")
    public CodeRecycleEntry adjustRecycleEntry(@PathVariable String id,
                                               @RequestBody RecycleAdjustRequest request) {
        return webScope(() -> requireOpsActionService().adjustRecycleEntry(
                id,
                request.status(),
                request.reason()
        ));
    }

    @PostMapping("/ops/ledgerEntry/{id}/inspect")
    @CustomActionEndpoint(value = "opsRecordQuery", title = "编码运维记录查询",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "id")
    public CodeLedgerInspection inspectLedgerEntry(@PathVariable String id) {
        return webScope(() -> requireOpsActionService().inspectLedgerEntry(id));
    }

    @PostMapping("/ops/ledgerEntry/{id}/release")
    @CustomActionEndpoint(value = "opsRecordManage", title = "编码运维记录管理",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "id")
    public CodeLedgerEntry releaseLedgerEntry(@PathVariable String id,
                                              @RequestBody(required = false) ReleaseLedgerRequest request) {
        return webScope(() -> requireOpsActionService().releaseStaleLedgerEntry(
                id,
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
