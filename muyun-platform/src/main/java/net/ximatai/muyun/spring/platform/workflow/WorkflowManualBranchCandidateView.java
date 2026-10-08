package net.ximatai.muyun.spring.platform.workflow;

import java.util.List;

public record WorkflowManualBranchCandidateView(
        String branchNodeKey,
        String branchTitle,
        WorkflowRouteMode routeMode,
        String selectorNodeKey,
        Boolean requireManualSelectionReason,
        List<Candidate> candidates,
        boolean selectionPending
) {
    public WorkflowManualBranchCandidateView(String branchNodeKey, WorkflowRouteMode routeMode,
            String selectorNodeKey, Boolean requireManualSelectionReason, List<Candidate> candidates) {
        this(branchNodeKey, branchNodeKey, routeMode, selectorNodeKey, requireManualSelectionReason, candidates);
    }

    public WorkflowManualBranchCandidateView(String branchNodeKey, String branchTitle, WorkflowRouteMode routeMode,
            String selectorNodeKey, Boolean requireManualSelectionReason, List<Candidate> candidates) {
        this(branchNodeKey, branchTitle, routeMode, selectorNodeKey, requireManualSelectionReason, candidates,
                candidates != null && candidates.stream().anyMatch(candidate -> candidate.routeStatus() == WorkflowRouteStatus.CANDIDATE));
    }

    public WorkflowManualBranchCandidateView {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public record Candidate(
            String routeId,
            String routeKey,
            String targetNodeKey,
            WorkflowNodeType targetNodeType,
            WorkflowRouteStatus routeStatus,
            Boolean defaultRoute,
            String title,
            String targetNodeTitle,
            Boolean conditionMatched,
            Boolean recommended
    ) {
        public Candidate(String routeId, String routeKey, String targetNodeKey, WorkflowNodeType targetNodeType,
                         WorkflowRouteStatus routeStatus, Boolean defaultRoute) {
            this(routeId, routeKey, targetNodeKey, targetNodeType, routeStatus, defaultRoute,
                    routeKey, targetNodeKey, false, false);
        }
    }
}
