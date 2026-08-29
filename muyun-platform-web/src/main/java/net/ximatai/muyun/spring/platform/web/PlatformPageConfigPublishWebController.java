package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.spring.web.BusinessMutationChange;
import net.ximatai.muyun.spring.web.BusinessMutationRecordIdSource;
import net.ximatai.muyun.spring.web.BusinessMutationResult;
import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigPublishService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplateService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = "platform.page_config_publish", title = "平台页面配置发布")
@RequestMapping("/platform.page_config_publish")
public class PlatformPageConfigPublishWebController extends WebSupport<PlatformPageConfigPublishService>
        implements SystemScope<PlatformPageConfigPublishService> {
    @PostMapping("/ui-configs/{id}/publish")
    @CustomActionEndpoint(value = "publishUiConfig", title = "发布 UI 配置", level = PlatformActionLevel.RECORD)
    @BusinessMutationResult(code = "platform.ui-config.published", message = "UI 配置已发布",
            change = BusinessMutationChange.UPDATED, module = PlatformUiConfigService.class,
            recordIdSource = BusinessMutationRecordIdSource.PATH_VARIABLE, recordId = "id")
    public int publishUiConfig(@PathVariable String id,
                               @RequestParam(name = "version", required = false) Integer expectedVersion) {
        return webScope(() -> {
            service().publishUiConfig(id, expectedVersion);
            return 1;
        });
    }

    @PostMapping("/ui-configs/{id}/unpublish")
    @CustomActionEndpoint(value = "unpublishUiConfig", title = "取消发布 UI 配置", level = PlatformActionLevel.RECORD)
    @BusinessMutationResult(code = "platform.ui-config.unpublished", message = "UI 配置已取消发布",
            change = BusinessMutationChange.UPDATED, module = PlatformUiConfigService.class,
            recordIdSource = BusinessMutationRecordIdSource.PATH_VARIABLE, recordId = "id")
    public int unpublishUiConfig(@PathVariable String id,
                                 @RequestParam(name = "version", required = false) Integer expectedVersion) {
        return webScope(() -> {
            service().unpublishUiConfig(id, expectedVersion);
            return 1;
        });
    }

    @PostMapping("/query-templates/{id}/publish")
    @CustomActionEndpoint(value = "publishQueryTemplate", title = "发布查询模板", level = PlatformActionLevel.RECORD)
    @BusinessMutationResult(code = "platform.query-template.published", message = "查询模板已发布",
            change = BusinessMutationChange.UPDATED, module = PlatformQueryTemplateService.class,
            recordIdSource = BusinessMutationRecordIdSource.PATH_VARIABLE, recordId = "id")
    public int publishQueryTemplate(@PathVariable String id) {
        return webScope(() -> {
            service().publishQueryTemplate(id);
            return 1;
        });
    }

    @PostMapping("/query-templates/{id}/unpublish")
    @CustomActionEndpoint(value = "unpublishQueryTemplate", title = "取消发布查询模板",
            level = PlatformActionLevel.RECORD)
    @BusinessMutationResult(code = "platform.query-template.unpublished", message = "查询模板已取消发布",
            change = BusinessMutationChange.UPDATED, module = PlatformQueryTemplateService.class,
            recordIdSource = BusinessMutationRecordIdSource.PATH_VARIABLE, recordId = "id")
    public int unpublishQueryTemplate(@PathVariable String id) {
        return webScope(() -> {
            service().unpublishQueryTemplate(id);
            return 1;
        });
    }
}
