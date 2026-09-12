package net.ximatai.muyun.spring.iam.logging;

import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadPage;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPageRequest;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPageResult;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScopeResolver;
import net.ximatai.muyun.spring.platform.logging.BusinessLogGovernanceService;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * IAM boundary for login-audit administration.
 *
 * <p>The exact Web action determines the administrative visibility range. Callers cannot pass a
 * range or a log type, so the login-audit endpoint cannot accidentally widen into another audit
 * stream.</p>
 */
@Service
public class LoginAuditGovernanceService {
    public static final String MODULE_ALIAS = "iam.login_audit_log";
    public static final String QUERY_ACTION_CODE = "queryEvents";
    public static final String DETAIL_ACTION_CODE = "viewEvent";

    private final BusinessLogGovernanceService businessLogGovernanceService;
    private final BusinessLogReadScopeResolver readScopeResolver;

    public LoginAuditGovernanceService(@Nullable BusinessLogGovernanceService businessLogGovernanceService,
                                       BusinessLogReadScopeResolver readScopeResolver) {
        this.businessLogGovernanceService = businessLogGovernanceService;
        this.readScopeResolver = Objects.requireNonNull(readScopeResolver, "readScopeResolver must not be null");
    }

    /** Reads login facts only, constrained by the current administrator's query grant. */
    public BusinessLogReadPage query(BusinessLogQuery query) {
        return governanceService().queryLoginAudits(Objects.requireNonNull(query, "query must not be null"),
                readScopeResolver.resolve(MODULE_ALIAS, QUERY_ACTION_CODE));
    }

    /** Reads a standard HTTP page while retaining cursor-backed storage underneath. */
    public BusinessLogPageResult queryPage(BusinessLogQuery query, BusinessLogPageRequest page) {
        return governanceService().queryLoginAuditsPage(
                Objects.requireNonNull(query, "query must not be null"),
                readScopeResolver.resolve(MODULE_ALIAS, QUERY_ACTION_CODE),
                Objects.requireNonNull(page, "page must not be null"));
    }

    /** Finds a login fact only when it remains visible through the current detail grant. */
    public Optional<BusinessLogEvent> findDetail(String eventId) {
        return governanceService().findLoginAuditDetail(eventId,
                readScopeResolver.resolve(MODULE_ALIAS, DETAIL_ACTION_CODE));
    }

    private BusinessLogGovernanceService governanceService() {
        if (businessLogGovernanceService == null) {
            throw new IllegalStateException("Business-log governance is not configured");
        }
        return businessLogGovernanceService;
    }
}
