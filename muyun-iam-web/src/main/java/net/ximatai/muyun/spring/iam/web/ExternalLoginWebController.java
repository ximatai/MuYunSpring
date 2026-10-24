package net.ximatai.muyun.spring.iam.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.AnonymousEndpoint;
import net.ximatai.muyun.spring.iam.federation.ConditionalOnExternalIdentityFederation;
import net.ximatai.muyun.spring.iam.federation.ExternalLoginResults;
import net.ximatai.muyun.spring.iam.federation.ExternalLoginService;
import net.ximatai.muyun.spring.iam.web.security.BearerTokens;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HTTP entries of external sign-in and binding. The provider returns the browser to the workbench, which
 * completes the transaction here with the state and proof it kept; no session is issued to any other browser.
 */
@RestController
@ConditionalOnExternalIdentityFederation
@RequestMapping("/iam.auth/external")
public class ExternalLoginWebController {
    private final ExternalLoginService externalLoginService;

    public ExternalLoginWebController(ExternalLoginService externalLoginService) {
        this.externalLoginService = externalLoginService;
    }

    @AnonymousEndpoint
    @PostMapping("/{providerId}/login")
    public ExternalLoginResults.Start startLogin(@PathVariable String providerId,
                                                @RequestBody StartLoginRequest request) {
        return externalLoginService.startLogin(request == null ? null : request.tenantId(), providerId);
    }

    @PostMapping("/{providerId}/bind")
    public ExternalLoginResults.Start startBind(@PathVariable String providerId, HttpServletRequest request) {
        return externalLoginService.startBind(currentUser(), BearerTokens.from(request), providerId);
    }

    /** Completes a transaction with the callback parameters the provider returned to the workbench. */
    @AnonymousEndpoint
    @PostMapping("/{providerId}/complete")
    public ExternalLoginResults.Completion complete(@PathVariable String providerId,
                                                    @RequestBody CompleteRequest request,
                                                    HttpServletRequest httpRequest) {
        CompleteRequest body = request == null ? new CompleteRequest(null, null, null) : request;
        return externalLoginService.complete(providerId, body.state(), body.browserProof(), body.parameters(),
                httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"));
    }

    @GetMapping("/identities")
    public ExternalLoginResults.OwnIdentities identities() {
        return externalLoginService.ownIdentities(currentUser());
    }

    @PostMapping("/identities/{identityId}/delete")
    public void unbind(@PathVariable String identityId, HttpServletRequest request) {
        externalLoginService.unbindOwn(currentUser(), BearerTokens.from(request), identityId);
    }

    private static CurrentUser currentUser() {
        return CurrentUserContext.currentUser()
                .orElseThrow(() -> new AuthenticationRequiredException("current user context is not available"));
    }

    public record StartLoginRequest(String tenantId) {
    }

    /**
     * @param parameters every query parameter the provider appended to the workbench address, verified by the
     *                   provider's protocol on the server
     */
    public record CompleteRequest(String state, String browserProof, Map<String, String> parameters) {
    }
}
