package net.ximatai.muyun.spring.platform.web.code;

import net.ximatai.muyun.spring.platform.code.*;
import net.ximatai.muyun.spring.platform.web.PlatformStaticActionScope;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebScope;
import net.ximatai.muyun.spring.common.platform.*;
import org.springframework.web.bind.annotation.*;

/** Request receipts project the same code-rule save action, not an assistant-only write path. */
@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticActionScope(module = CodeRuleService.MODULE_ALIAS)
@RequestMapping("/platform.code_rule/save-requests")
public class CodeRuleSaveWebController extends net.ximatai.muyun.spring.web.WebSupport<CodeRuleService> {
    private final CodeRuleSaveService saves;
    public CodeRuleSaveWebController(CodeRuleSaveService saves) { this.saves = java.util.Objects.requireNonNull(saves); }
    @PostMapping("/{requestId}")
    @CustomActionEndpoint(value = "saveTree", title = "保存规则树", level = PlatformActionLevel.ANY, dataAuth = false)
    public CodeRule save(@PathVariable String requestId, @RequestBody CodeRule rule) {
        return webScope(() -> saves.save(requestId, rule));
    }
    @GetMapping("/{requestId}")
    @CustomActionEndpoint(value = "configurationQuery", title = "查询编码配置目标", level = PlatformActionLevel.LIST, dataAuth = false)
    public CodeRuleSaveService.Result lookup(@PathVariable String requestId, @RequestParam String moduleAlias,
            @RequestParam String entityAlias, @RequestParam String fieldName) {
        return webScope(() -> saves.lookup(requestId, moduleAlias, entityAlias, fieldName));
    }
    @PostMapping("/{requestId}/abandon")
    @CustomActionEndpoint(value = "saveTree", title = "保存规则树", level = PlatformActionLevel.ANY, dataAuth = false)
    public CodeRuleSaveService.Result abandon(@PathVariable String requestId, @RequestBody Target target) {
        return webScope(() -> saves.abandon(requestId, target.moduleAlias(), target.entityAlias(), target.fieldName()));
    }
    public record Target(String moduleAlias, String entityAlias, String fieldName) {}
}
