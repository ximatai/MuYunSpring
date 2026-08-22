package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceCandidateCriteriaTest {
    @Test
    void shouldCompilePresentDependenciesAndIgnoreMissingOptionalValues() {
        var criteria = ReferenceCandidateCriteria.from(List.of(
                ReferenceCandidateDependency.required("organizationId", "organizationId"),
                new ReferenceCandidateDependency("departmentId", "departmentId", false)),
                Map.of("organizationId", "organization-1"));

        assertThat(criteria.getClauses()).singleElement().satisfies(clause -> {
            assertThat(clause.getField()).isEqualTo("organizationId");
            assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.EQ);
            assertThat(clause.getValues()).containsExactly("organization-1");
        });
    }

    @Test
    void shouldCompileMissingRequiredDependencyToAnEmptyCandidateSet() {
        var criteria = ReferenceCandidateCriteria.from(
                List.of(ReferenceCandidateDependency.required("organizationId", "organizationId")), Map.of());

        assertThat(criteria.getClauses()).singleElement().satisfies(clause -> {
            assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.RAW);
            assertThat(clause.getValues()).singleElement()
                    .extracting(value -> ((net.ximatai.muyun.database.core.orm.SqlRawCondition) value).getSql())
                    .isEqualTo("1 = 0");
        });
    }
}
