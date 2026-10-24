package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.common.platform.AnonymousEndpoint;
import net.ximatai.muyun.spring.iam.federation.ExternalIdentityFederationSettings;
import net.ximatai.muyun.spring.iam.federation.ExternalIdentityUris;
import net.ximatai.muyun.spring.iam.federation.MockIdentityProviderCondition;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * Development-only stand-in for a provider's authorization page. It returns to the platform callback with
 * the external user id typed by the developer; the mock protocol refuses to start outside development mode.
 */
@RestController
@Conditional(MockIdentityProviderCondition.class)
@RequestMapping("/iam.auth/external/mock")
public class MockIdentityProviderWebController {
    private final ExternalIdentityFederationSettings settings;

    public MockIdentityProviderWebController(ExternalIdentityFederationSettings settings) {
        this.settings = settings;
    }

    @AnonymousEndpoint
    @GetMapping(value = "/authorize", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> authorize(@RequestParam("state") String state,
                                            @RequestParam("redirect_uri") String redirectUri) {
        // Only the platform's own callbacks are accepted, so the page cannot be used as an open redirect.
        String callbackPrefix = ExternalIdentityUris.resolve(settings.apiBaseUrl(), "iam.auth/external/").toString();
        if (!redirectUri.startsWith(callbackPrefix) || !redirectUri.endsWith("/callback")) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("invalid redirect_uri");
        }
        String html = """
                <!doctype html>
                <html lang="zh-CN">
                <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>模拟身份源授权</title></head>
                <body style="font-family: sans-serif; max-width: 420px; margin: 64px auto; padding: 0 16px">
                <h1 style="font-size: 20px">模拟身份源授权（仅开发环境）</h1>
                <p>输入要模拟的外部用户 ID，确认后按真实流程回调平台。</p>
                <form method="get" action="%s">
                <input type="hidden" name="state" value="%s">
                <label>外部用户 ID <input name="mockUserId" required autofocus style="width: 100%%"></label>
                <p><button type="submit">确认授权</button></p>
                </form>
                <script>
                document.querySelector('form').addEventListener('submit', function (event) {
                  var input = event.target.querySelector('[name=mockUserId]');
                  var code = document.createElement('input');
                  code.type = 'hidden'; code.name = 'code'; code.value = 'mock:' + input.value.trim();
                  event.target.appendChild(code); input.disabled = true;
                });
                </script>
                </body>
                </html>
                """.formatted(HtmlUtils.htmlEscape(redirectUri), HtmlUtils.htmlEscape(state));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.TEXT_HTML).body(html);
    }
}
