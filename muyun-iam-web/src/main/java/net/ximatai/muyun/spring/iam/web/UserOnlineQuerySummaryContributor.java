package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.iam.user.UserSessionPresenceService;
import net.ximatai.muyun.spring.platform.web.ListQuerySummaryContributor;
import net.ximatai.muyun.spring.web.WebListQuerySummaryItem;
import org.springframework.stereotype.Component;

/** IAM contribution of the online-account metric to the generic list-summary runtime. */
@Component
final class UserOnlineQuerySummaryContributor implements ListQuerySummaryContributor {
    private static final String MODULE = "iam.user";
    private static final String KEY = "iam.active-user-count";
    private final UserSessionPresenceService presenceService;

    UserOnlineQuerySummaryContributor(UserSessionPresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @Override
    public String moduleAlias() {
        return MODULE;
    }

    @Override
    public String contributorKey() {
        return KEY;
    }

    @Override
    public WebListQuerySummaryItem summarize(ListQuerySummaryContext context) {
        return new WebListQuerySummaryItem(context.summaryKey(),
                context.count(Criteria.copyOf(presenceService.activeAccountCriteria())));
    }
}
