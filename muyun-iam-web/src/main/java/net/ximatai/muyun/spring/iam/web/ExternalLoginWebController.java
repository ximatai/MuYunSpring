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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HTTP entries of external sign-in and binding. The provider calls back the platform, which then sends the
 * browser to the workbench with only the transaction id; the browser redeems it with the proof it kept.
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

    /** Provider callback; answers with a redirect so no token or secret ever travels in the URL. */
    @AnonymousEndpoint
    @GetMapping("/{providerId}/callback")
    public ResponseEntity<Void> callback(@PathVariable String providerId,
                                         @RequestParam Map<String, String> parameters) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, externalLoginService.complete(providerId, parameters).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    @AnonymousEndpoint
    @PostMapping("/transactions/{transactionId}/redeem")
    public ExternalLoginResults.Redemption redeem(@PathVariable String transactionId,
                                                  @RequestBody RedeemRequest request,
                                                  HttpServletRequest httpRequest) {
        return externalLoginService.redeem(transactionId, request == null ? null : request.browserProof(),
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

    public record RedeemRequest(String browserProof) {
    }
}
