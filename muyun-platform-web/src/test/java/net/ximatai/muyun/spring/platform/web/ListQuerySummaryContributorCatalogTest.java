package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.WebListQuerySummaryItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListQuerySummaryContributorCatalogTest {
    @Test
    void shouldRejectDuplicateModuleAndContributorKeyAtStartup() {
        assertThatThrownBy(() -> new ListQuerySummaryContributorCatalog(List.of(
                contributor("iam.user", "online"), contributor("iam.user", "online"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate list query summary contributor: iam.user.online");
    }

    @Test
    void shouldResolveRegisteredContributorByStableIdentity() {
        ListQuerySummaryContributor contributor = contributor("iam.user", "online");
        ListQuerySummaryContributorCatalog catalog = new ListQuerySummaryContributorCatalog(List.of(contributor));

        assertThat(catalog.require("iam.user", "online")).isSameAs(contributor);
        assertThatThrownBy(() -> catalog.require("iam.user", "missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no list query summary contributor: iam.user.missing");
    }

    private static ListQuerySummaryContributor contributor(String moduleAlias, String contributorKey) {
        return new ListQuerySummaryContributor() {
            @Override public String moduleAlias() { return moduleAlias; }
            @Override public String contributorKey() { return contributorKey; }
            @Override public WebListQuerySummaryItem summarize(ListQuerySummaryContext context) {
                return new WebListQuerySummaryItem(context.summaryKey(), 0);
            }
        };
    }
}
