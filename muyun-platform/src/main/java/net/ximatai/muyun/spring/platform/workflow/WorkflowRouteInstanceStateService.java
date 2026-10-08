package net.ximatai.muyun.spring.platform.workflow;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class WorkflowRouteInstanceStateService {
    public void applyActivation(List<WorkflowRouteInstance> routes, WorkflowActivationResult activation,
                                String operatorId, Instant operatedAt) {
        if (routes == null || activation == null) {
            return;
        }
        Set<String> traversedRouteKeys = new HashSet<>(activation.traversedRouteKeys());
        Instant now = operatedAt == null ? Instant.now() : operatedAt;
        for (WorkflowRouteInstance route : routes) {
            if (route == null || !traversedRouteKeys.contains(route.getRouteKey())) {
                continue;
            }
            if (route.getRouteStatus() == WorkflowRouteStatus.CLOSED || route.getRouteStatus() == WorkflowRouteStatus.DROPPED
                    || route.getRouteStatus() == WorkflowRouteStatus.INEFFECTIVE) continue;
            route.setRouteStatus(WorkflowRouteStatus.EFFECTIVE);
            route.setSelectedBy(operatorId);
            route.setSelectedAt(now);
        }
    }
}
