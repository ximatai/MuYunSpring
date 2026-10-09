package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class WorkflowRouteRuntimeService {
    public WorkflowRouteInstance effectiveRoute(WorkflowRouteInstance route, WorkflowRouteReason reason,
                                                String operatorId, Instant selectedAt) {
        return effectiveRoute(route, reason, operatorId, selectedAt, null);
    }

    public WorkflowRouteInstance effectiveRoute(WorkflowRouteInstance route, WorkflowRouteReason reason,
                                                String operatorId, Instant selectedAt, String selectedReason) {
        route.setRouteStatus(WorkflowRouteStatus.EFFECTIVE);
        route.setRouteReason(reason);
        if (reason != WorkflowRouteReason.MANUAL_SELECTED) {
            route.setConditionMatched(reason == WorkflowRouteReason.CONDITION_MATCHED);
        }
        route.setSelectedBy(operatorId);
        route.setSelectedAt(selectedAt);
        if (reason == WorkflowRouteReason.MANUAL_SELECTED) {
            route.setSelectedReason(textOrNull(selectedReason));
        }
        return route;
    }

    /** Attach the manual decision without reopening a path that already reached convergence. */
    public void recordManualSelection(WorkflowRouteInstance route, String operatorId,
                                      Instant selectedAt, String selectedReason) {
        if (route.getRouteStatus() == WorkflowRouteStatus.CANDIDATE
                || route.getRouteStatus() == WorkflowRouteStatus.EFFECTIVE) {
            route.setRouteStatus(WorkflowRouteStatus.EFFECTIVE);
            route.setRouteReason(WorkflowRouteReason.MANUAL_SELECTED);
        }
        route.setSelectedBy(operatorId);
        route.setSelectedAt(selectedAt);
        route.setSelectedReason(textOrNull(selectedReason));
    }

    public WorkflowRouteInstance ineffectiveRoute(WorkflowRouteInstance route, WorkflowRouteReason reason,
                                                  String operatorId, Instant selectedAt) {
        route.setRouteStatus(WorkflowRouteStatus.INEFFECTIVE);
        route.setRouteReason(reason);
        route.setConditionMatched(false);
        route.setSelectedBy(operatorId);
        route.setSelectedAt(selectedAt);
        return route;
    }

    public WorkflowConvergeDecision handleConvergeArrival(WorkflowRouteInstance arrivedRoute,
                                                          List<WorkflowRouteInstance> siblingRoutes,
                                                          WorkflowConvergeMode convergeMode,
                                                          Integer convergeRatio,
                                                          Instant arrivedAt) {
        if (arrivedRoute == null) {
            throw new PlatformException("arrived workflow route must not be null");
        }
        List<WorkflowRouteInstance> siblings = siblingRoutes == null ? List.of() : siblingRoutes;
        List<WorkflowRouteInstance> scoped = new ArrayList<>(siblings.stream()
                .filter(route -> sameConvergeScope(arrivedRoute, route))
                .toList());
        boolean containsArrivedRoute = scoped.stream().anyMatch(route -> sameRoute(arrivedRoute, route));
        if (!containsArrivedRoute) {
            scoped.add(arrivedRoute);
        }
        arrivedRoute.setArrivedAt(arrivedAt);
        arrivedRoute.setRouteReason(WorkflowRouteReason.CONVERGE_REACHED);

        List<WorkflowRouteInstance> effectiveRoutes = scoped.stream()
                .filter(route -> route.getRouteStatus() == WorkflowRouteStatus.EFFECTIVE
                        || route.getRouteStatus() == WorkflowRouteStatus.CLOSED)
                .toList();
        if (effectiveRoutes.isEmpty()) {
            return WorkflowConvergeDecision.waiting();
        }
        List<WorkflowRouteInstance> arrivedRoutes = effectiveRoutes.stream()
                .filter(route -> route.getArrivedAt() != null || route == arrivedRoute)
                .toList();
        if (!canPass(convergeMode, convergeRatio, effectiveRoutes.size(), arrivedRoutes.size())) {
            return WorkflowConvergeDecision.waiting();
        }
        List<WorkflowRouteInstance> toClose = new ArrayList<>(arrivedRoutes);
        List<WorkflowRouteInstance> toDrop = effectiveRoutes.stream()
                .filter(route -> route.getArrivedAt() == null && route != arrivedRoute)
                .toList();
        toClose.forEach(route -> {
            route.setRouteStatus(WorkflowRouteStatus.CLOSED);
            route.setRouteReason(WorkflowRouteReason.NORMAL_CONVERGED);
        });
        toDrop.forEach(route -> {
            route.setRouteStatus(WorkflowRouteStatus.DROPPED);
            route.setRouteReason(WorkflowRouteReason.NORMAL_CONVERGED);
            route.setClosedByRouteId(arrivedRoute.getId());
            route.setClosedReason("workflow converge passed");
        });
        return new WorkflowConvergeDecision(true, toClose, toDrop);
    }

    public boolean sameConvergeScope(WorkflowRouteInstance left, WorkflowRouteInstance right) {
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.getInstanceId(), right.getInstanceId())
                && Objects.equals(left.getBranchNodeKey(), right.getBranchNodeKey())
                && Objects.equals(left.getBranchRunId(), right.getBranchRunId())
                && Objects.equals(left.getConvergeNodeKey(), right.getConvergeNodeKey())
                && Objects.equals(left.getConvergeRunId(), right.getConvergeRunId())
                && Objects.equals(left.getParentRouteId(), right.getParentRouteId());
    }

    private boolean sameRoute(WorkflowRouteInstance left, WorkflowRouteInstance right) {
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.getId(), right.getId())
                || Objects.equals(left.getRouteRunId(), right.getRouteRunId());
    }

    private String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean canPass(WorkflowConvergeMode convergeMode, Integer convergeRatio,
                            int effectiveCount, int arrivedCount) {
        WorkflowConvergeMode mode = convergeMode == null ? WorkflowConvergeMode.ALL : convergeMode;
        return switch (mode) {
            case ANY -> arrivedCount > 0;
            case ALL -> arrivedCount == effectiveCount;
            case RATIO -> arrivedCount * 100 >= (long) effectiveCount * ratio(convergeRatio);
        };
    }

    private int ratio(Integer convergeRatio) {
        if (convergeRatio == null || convergeRatio <= 0 || convergeRatio > 100) {
            throw new PlatformException("workflow converge ratio must be between 1 and 100");
        }
        return convergeRatio;
    }
}
