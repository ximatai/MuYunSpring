package net.ximatai.muyun.spring.iam.web.workflow;

import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowUserTitleResolver;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class IamWorkflowUserTitleResolver implements WorkflowUserTitleResolver {
    private final UserAccountService userAccountService;

    public IamWorkflowUserTitleResolver(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @Override
    public Map<String, String> titles(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> titles = new LinkedHashMap<>();
        for (String userId : userIds) {
            if (userId == null || userId.isBlank() || titles.containsKey(userId)) {
                continue;
            }
            UserAccount user;
            // Workflow actors may include platform accounts; tenant accounts stay in the current tenant.
            try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter("workflow actor display title")) {
                user = userAccountService.select(userId);
            }
            if (user != null && user.getTenantId() != null && !user.getTenantId().isBlank()
                    && !TenantContext.currentTenantId().filter(user.getTenantId()::equals).isPresent()) continue;
            if (user != null) {
                String title = user.getTitle() == null || user.getTitle().isBlank()
                        ? user.getUsername() : user.getTitle();
                if (title != null && !title.isBlank()) titles.put(userId, title);
            }
        }
        return Map.copyOf(titles);
    }
}
