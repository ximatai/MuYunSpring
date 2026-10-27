package net.ximatai.muyun.spring.iam.web.security;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserProvider;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import net.ximatai.muyun.spring.iam.user.PersonalAccessTokenService;
import net.ximatai.muyun.spring.common.identity.AuthenticatedUser;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

public class BearerTokenCurrentUserProvider implements CurrentUserProvider {
    private final UserSessionService userSessionService;
    private final Optional<PersonalAccessTokenService> personalAccessTokens;

    public BearerTokenCurrentUserProvider(UserSessionService userSessionService) {
        this(userSessionService, Optional.empty());
    }

    public BearerTokenCurrentUserProvider(UserSessionService userSessionService,
                                         Optional<PersonalAccessTokenService> personalAccessTokens) {
        this.userSessionService = userSessionService;
        this.personalAccessTokens = java.util.Objects.requireNonNull(personalAccessTokens);
    }

    @Override
    public Optional<CurrentUser> currentUser() {
        return authenticatedUser().map(AuthenticatedUser::user);
    }

    @Override
    public Optional<AuthenticatedUser> authenticatedUser() {
        return request().flatMap(request -> {
            String token = bearerToken(request);
            if (token != null && token.startsWith(PersonalAccessTokenService.PREFIX)) {
                return personalAccessTokens.map(service -> service.authenticate(token));
            }
            return userSessionService.currentUser(token).map(user -> new AuthenticatedUser(user,
                    "session", userSessionService.currentSessionId(token).orElse(null)));
        });
    }

    private Optional<HttpServletRequest> request() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return Optional.of(servletAttributes.getRequest());
        }
        return Optional.empty();
    }

    private String bearerToken(HttpServletRequest request) {
        return BearerTokens.from(request);
    }
}
