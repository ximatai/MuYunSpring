package net.ximatai.muyun.spring.iam.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.iam.user.*;
import net.ximatai.muyun.spring.iam.web.security.BearerTokens;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebScope;
import net.ximatai.muyun.spring.platform.web.StaticModuleTenantScopePolicy;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;

@RestController
@RequestMapping("/iam.auth/access-tokens")
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = PersonalAccessTokenService.MODULE_ALIAS, title = "个人访问密钥")
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@StaticModuleTenantScopePolicy(requireActiveTenant = false)
public class PersonalAccessTokenWebController {
    private final PersonalAccessTokenService tokens;
    public PersonalAccessTokenWebController(PersonalAccessTokenService tokens) { this.tokens = tokens; }

    @GetMapping
    @CustomActionEndpoint(value = "query", title = "查看个人访问密钥", accessMode = ActionAccessMode.LOGIN_REQUIRED,
            actionAuth = false, dataAuth = false)
    public List<PersonalAccessTokenView> list(HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        return tokens.list(BearerTokens.from(request));
    }
    @GetMapping("/capabilities")
    @CustomActionEndpoint(value = "accessTokenCapabilities", title = "查看个人访问密钥能力", accessMode = ActionAccessMode.LOGIN_REQUIRED,
            actionAuth = false, dataAuth = false)
    public Capabilities capabilities(HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        return new Capabilities(tokens.canCreate(BearerTokens.from(request)));
    }
    public record Capabilities(boolean canCreate) {}

    @PostMapping
    @CustomActionEndpoint(value = PersonalAccessTokenService.CREATE_ACTION, title = "创建个人访问密钥",
            level = PlatformActionLevel.LIST, actionAuth = true, dataAuth = false)
    public IssuedPersonalAccessToken create(@RequestBody CreateRequest body, HttpServletRequest request,
                                            HttpServletResponse response) {
        noStore(response);
        return tokens.create(BearerTokens.from(request), body == null ? null : body.name(),
                body == null ? null : body.validDays());
    }
    @PostMapping("/{id}/revoke")
    @CustomActionEndpoint(value = "revoke", title = "撤销个人访问密钥", accessMode = ActionAccessMode.LOGIN_REQUIRED,
            actionAuth = false, dataAuth = false)
    public void revoke(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        tokens.revoke(BearerTokens.from(request), id);
    }
    @DeleteMapping("/{id}")
    @CustomActionEndpoint(value = "delete", title = "删除个人访问密钥", accessMode = ActionAccessMode.LOGIN_REQUIRED,
            actionAuth = false, dataAuth = false)
    public void delete(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        tokens.delete(BearerTokens.from(request), id);
    }
    private static void noStore(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    public record CreateRequest(String name, Integer validDays) {}
}
