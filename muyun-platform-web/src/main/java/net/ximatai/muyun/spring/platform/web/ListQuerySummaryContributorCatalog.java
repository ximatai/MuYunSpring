package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable directory of domain-owned list query summary contributors.
 *
 * <p>A page declaration refers to a contributor by the pair of its module alias and contributor
 * key.  Keeping that pair enumerable lets static declarations fail during plan compilation and
 * dynamic candidates fail before their published plan is installed, rather than failing on the
 * first query that happens to request the footer.</p>
 */
@Component
public class ListQuerySummaryContributorCatalog {
    private final Map<ContributorId, ListQuerySummaryContributor> contributors;

    public ListQuerySummaryContributorCatalog(List<ListQuerySummaryContributor> contributors) {
        LinkedHashMap<ContributorId, ListQuerySummaryContributor> registered = new LinkedHashMap<>();
        for (ListQuerySummaryContributor contributor : contributors == null ? List.<ListQuerySummaryContributor>of() : contributors) {
            if (contributor == null) {
                throw new IllegalArgumentException("list query summary contributor must not be null");
            }
            ContributorId id = ContributorId.of(contributor.moduleAlias(), contributor.contributorKey());
            if (registered.putIfAbsent(id, contributor) != null) {
                throw new IllegalArgumentException("duplicate list query summary contributor: " + id);
            }
        }
        this.contributors = Map.copyOf(registered);
    }

    public ListQuerySummaryContributor require(String moduleAlias, String contributorKey) {
        ContributorId id = ContributorId.of(moduleAlias, contributorKey);
        ListQuerySummaryContributor contributor = contributors.get(id);
        if (contributor == null) {
            throw new IllegalArgumentException("no list query summary contributor: " + id);
        }
        return contributor;
    }

    /** Verifies every domain-owned metric referenced by one already-compiled page plan. */
    public void validate(ModuleExecutionPlan plan) {
        if (plan == null || plan.uiDescriptor().page() == null || plan.uiDescriptor().page().list() == null) {
            return;
        }
        plan.uiDescriptor().page().list().querySummaries().stream()
                .filter(summary -> summary.source() == PageListQuerySummaryDefinition.Source.CONTRIBUTOR)
                .forEach(summary -> require(plan.moduleAlias(), summary.contributorKey()));
    }

    private record ContributorId(String moduleAlias, String contributorKey) {
        private static ContributorId of(String moduleAlias, String contributorKey) {
            String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
            if (contributorKey == null || contributorKey.isBlank()) {
                throw new IllegalArgumentException("list query summary contributor key must not be blank");
            }
            return new ContributorId(validModuleAlias, contributorKey.trim());
        }

        @Override
        public String toString() {
            return moduleAlias + "." + contributorKey;
        }
    }
}
