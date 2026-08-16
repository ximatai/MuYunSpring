package net.ximatai.muyun.spring.platform.web.code;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebScope;
import net.ximatai.muyun.spring.web.QueryViewWeb;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.code.CodeOpsActionService;
import net.ximatai.muyun.spring.platform.code.CodeSequenceBaselineResult;
import net.ximatai.muyun.spring.platform.code.CodeSequenceState;
import net.ximatai.muyun.spring.platform.code.CodeSequenceStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;


@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = CodeSequenceStateService.MODULE_ALIAS, title = "编码序列状态")
@RequestMapping({"/platform.code_sequence_state", "/platform/code/sequence-state"})
public class CodeSequenceStateWebController extends WebSupport<CodeSequenceStateService> implements
        QueryViewWeb<CodeSequenceState, CodeSequenceStateService> {

    private CodeOpsActionService opsActionService;

    public CodeSequenceStateWebController() {
    }

    @Autowired
    public CodeSequenceStateWebController(CodeOpsActionService opsActionService) {
        this.opsActionService = opsActionService;
    }

    @PostMapping("/adjust/{id}")
    @CustomActionEndpoint(value = "adjustBaseline", title = "调整序列基线",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "id")
    public CodeSequenceBaselineResult adjustBaseline(@PathVariable String id,
                                                     @RequestBody AdjustBaselineRequest request) {
        return webScope(() -> requireOpsActionService().adjustSequenceState(
                id,
                request.currentValue(),
                request.reason()
        ));
    }

    private CodeOpsActionService requireOpsActionService() {
        if (opsActionService == null) {
            throw new IllegalStateException("Code ops action service is not configured");
        }
        return opsActionService;
    }

    public record AdjustBaselineRequest(Long currentValue, String reason) {
    }
}
