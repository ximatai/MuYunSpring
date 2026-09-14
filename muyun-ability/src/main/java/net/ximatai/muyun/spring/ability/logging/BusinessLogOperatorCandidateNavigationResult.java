package net.ximatai.muyun.spring.ability.logging;

/** Operator page plus the event-time scopes from which it was derived. */
public record BusinessLogOperatorCandidateNavigationResult(
        BusinessLogOperatorCandidatePage candidates,
        BusinessLogOperatorNavigation navigation,
        boolean showTenantNavigation
) {
    public BusinessLogOperatorCandidateNavigationResult {
        if (candidates == null || navigation == null) {
            throw new IllegalArgumentException("candidates and navigation must not be null");
        }
    }
}
