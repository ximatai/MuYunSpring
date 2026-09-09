package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.config.LowCodeConfigHealthReport;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleConfigArchiveFacade;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleConfigArchiveResult;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleConfigVersion;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleHealthContext;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleHealthService;
import net.ximatai.muyun.spring.platform.config.LowCodeModulePackage;
import net.ximatai.muyun.spring.platform.config.LowCodeModulePackageExchangeService;
import net.ximatai.muyun.spring.platform.config.LowCodeModulePackageImportDraft;
import net.ximatai.muyun.spring.platform.config.LowCodeModulePackageImportService;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleTemplate;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleTemplateInstantiationRequest;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleTemplateService;
import net.ximatai.muyun.spring.platform.config.LowCodePackageDryRunResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = "platform.low_code_governance", title = "平台低代码治理")
@RequestMapping({"/platform.low_code_governance", "/platform/low-code-governance"})
public class LowCodeGovernanceWebController extends WebSupport<LowCodeModuleConfigArchiveFacade>
        implements SystemScope<LowCodeModuleConfigArchiveFacade> {
    private final LowCodeModuleHealthService healthService;
    private final LowCodeModulePackageExchangeService exchangeService;
    private final LowCodeModulePackageImportService importService;
    private final LowCodeModuleTemplateService templateService;

    public LowCodeGovernanceWebController(LowCodeModuleConfigArchiveFacade archiveFacade,
                                          LowCodeModuleHealthService healthService,
                                          LowCodeModulePackageExchangeService exchangeService,
                                          LowCodeModulePackageImportService importService,
                                          LowCodeModuleTemplateService templateService) {
        this.service = archiveFacade;
        this.healthService = healthService;
        this.exchangeService = exchangeService;
        this.importService = importService;
        this.templateService = templateService;
    }

    @PostMapping("/packages/health")
    @CustomActionEndpoint(value = "checkPackageHealth", title = "检查配置包健康度",
            level = PlatformActionLevel.LIST)
    public LowCodeConfigHealthReport checkPackageHealth(@RequestBody LowCodeModulePackage modulePackage) {
        return webScope(() -> healthService.check(LowCodeModuleHealthContext.ofPackage(modulePackage)));
    }

    @PostMapping("/packages/archive")
    @CustomActionEndpoint(value = "archivePackage", title = "归档配置包", level = PlatformActionLevel.LIST)
    public LowCodeModuleConfigArchiveResult archivePackage(@RequestBody ArchivePackageRequest request) {
        return webScope(() -> service().archive(request.modulePackage(), request.operatorId(), request.remark()));
    }

    @PostMapping("/modules/{moduleAlias}/versions/{versionId}/switch-current")
    @CustomActionEndpoint(value = "switchCurrentPackageVersion", title = "切换当前配置包版本",
            level = PlatformActionLevel.RECORD, recordIdPathVariable = "versionId")
    public LowCodeModuleConfigVersion switchCurrentPackageVersion(@PathVariable String moduleAlias,
                                                            @PathVariable String versionId) {
        return webScope(() -> service().switchCurrentVersion(moduleAlias, versionId));
    }

    @GetMapping("/modules/{moduleAlias}/package")
    @CustomActionEndpoint(value = "exportCurrentPackage", title = "导出当前配置包",
            level = PlatformActionLevel.RECORD, recordIdPathVariable = "moduleAlias")
    public LowCodeModulePackage exportCurrentPackage(@PathVariable String moduleAlias) {
        return webScope(() -> exchangeService.parsePackage(exchangeService.exportCurrentPackage(moduleAlias)));
    }

    @GetMapping("/versions/{versionId}/package")
    @CustomActionEndpoint(value = "exportVersionPackage", title = "导出指定版本配置包",
            level = PlatformActionLevel.RECORD, recordIdPathVariable = "versionId")
    public LowCodeModulePackage exportVersionPackage(@PathVariable String versionId) {
        return webScope(() -> exchangeService.parsePackage(exchangeService.exportVersionPackage(versionId)));
    }

    @PostMapping("/imports/dry-run")
    @CustomActionEndpoint(value = "dryRunImportPackage", title = "导入配置包预检",
            level = PlatformActionLevel.LIST)
    public LowCodePackageDryRunResult dryRunImportPackage(@RequestBody LowCodeModulePackage modulePackage) {
        return webScope(() -> exchangeService.dryRunImport(modulePackage));
    }

    @PostMapping("/imports/drafts")
    @CustomActionEndpoint(value = "prepareImportDraft", title = "准备导入草稿", level = PlatformActionLevel.LIST)
    public LowCodeModulePackageImportDraft prepareImportDraft(@RequestBody LowCodeModulePackage modulePackage) {
        return webScope(() -> importService.prepareDraft(modulePackage));
    }

    @PostMapping("/imports/drafts/archive")
    @CustomActionEndpoint(value = "archiveImportDraft", title = "归档导入草稿", level = PlatformActionLevel.LIST)
    public LowCodeModuleConfigArchiveResult archiveImportDraft(@RequestBody ArchiveImportDraftRequest request) {
        return webScope(() -> importService.archiveDraft(request.draft(), request.operatorId(), request.remark()));
    }

    @PostMapping("/templates/from-version")
    @CustomActionEndpoint(value = "createTemplateFromVersion", title = "从版本创建模板",
            level = PlatformActionLevel.LIST)
    public LowCodeModuleTemplate createTemplateFromVersion(@RequestBody CreateTemplateFromVersionRequest request) {
        return webScope(() -> templateService.createTemplateFromVersion(
                request.templateAlias(), request.title(), request.versionId()));
    }

    @PostMapping("/templates/instantiate")
    @CustomActionEndpoint(value = "instantiateTemplate", title = "实例化模板",
            level = PlatformActionLevel.LIST)
    public LowCodeModulePackage instantiateTemplate(@RequestBody InstantiateTemplateRequest request) {
        return webScope(() -> templateService.instantiate(request.template(), request.request()));
    }

    public record ArchivePackageRequest(
            LowCodeModulePackage modulePackage,
            String operatorId,
            String remark
    ) {
    }

    public record ArchiveImportDraftRequest(
            LowCodeModulePackageImportDraft draft,
            String operatorId,
            String remark
    ) {
    }

    public record CreateTemplateFromVersionRequest(
            String templateAlias,
            String title,
            String versionId
    ) {
    }

    public record InstantiateTemplateRequest(
            LowCodeModuleTemplate template,
            LowCodeModuleTemplateInstantiationRequest request
    ) {
    }
}
