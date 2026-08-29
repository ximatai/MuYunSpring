package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionPublishService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionService;
import net.ximatai.muyun.spring.web.BusinessMutationChange;
import net.ximatai.muyun.spring.web.BusinessMutationRecordIdSource;
import net.ximatai.muyun.spring.web.BusinessMutationResult;
import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The explicit publication boundary; ordinary revision CRUD is draft-only. */
@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = "platform.presentation_publish", title = "平台页面呈现发布")
@RequestMapping("/platform.presentation_publish")
public class PlatformPresentationRevisionPublishWebController
        extends WebSupport<PlatformPresentationRevisionPublishService>
        implements SystemScope<PlatformPresentationRevisionPublishService> {

    @PostMapping("/revisions/{id}/publish")
    @CustomActionEndpoint(value = "publish", title = "发布页面修订", level = PlatformActionLevel.RECORD)
    @BusinessMutationResult(code = "platform.presentation-revision.published", message = "页面修订已发布",
            change = BusinessMutationChange.UPDATED, module = PlatformPresentationRevisionService.class,
            recordIdSource = BusinessMutationRecordIdSource.PATH_VARIABLE, recordId = "id")
    public int publish(@PathVariable String id) {
        return webScope(() -> {
            service().publish(id);
            return 1;
        });
    }
}
