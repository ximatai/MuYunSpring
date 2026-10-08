package net.ximatai.muyun.spring.platform.workflow;

import java.util.List;

/** IAM adapters supply people, not workflow states or execution decisions. */
public interface WorkflowIdentityResolver {
    List<String> resolve(WorkflowParticipantRule rule, String originUserId, String organizationId);
    boolean isEnabledUser(String userId);
}
