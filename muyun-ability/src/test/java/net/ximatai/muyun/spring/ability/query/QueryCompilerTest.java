package net.ximatai.muyun.spring.ability.query;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.database.core.orm.SortDirection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryCompilerTest {
    @Test
    void shouldCompileDeclaredConditionsQuickSearchExternalCriteriaAndSorts() {
        QueryDescriptor descriptor = descriptor();
        QueryCompiler compiler = new QueryCompiler(descriptor);
        QueryRequest request = new QueryRequest(
                List.of(new QueryCondition("enabled", QueryOperator.EQ, List.of(true), null)),
                null,
                Map.of(),
                List.of(new QuerySort("code", true)),
                null,
                null,
                Map.of("scope", "dept-1"),
                "alice",
                List.of(),
                false,
                null
        );

        Criteria criteria = compiler.criteria(request);
        Sort[] sorts = compiler.sorts(request);

        assertThat(containsCondition(criteria, "enabled", true)).isTrue();
        assertThat(containsCondition(criteria, "departmentId", "dept-1")).isTrue();
        assertThat(containsCondition(criteria, "code", "%alice%")).isTrue();
        assertThat(containsCondition(criteria, "title", "%alice%")).isTrue();
        assertThat(sorts).hasSize(1);
        assertThat(sorts[0].getField()).isEqualTo("code");
        assertThat(sorts[0].getDirection()).isEqualTo(SortDirection.DESC);
    }

    @Test
    void shouldTreatQuickSearchWildcardsAsLiterals() {
        QueryCompiler compiler = new QueryCompiler(descriptor());
        QueryRequest request = new QueryRequest(List.of(), null, Map.of(), List.of(), null, null,
                Map.of(), "a%b_c\\d", List.of(), false, null);

        Criteria criteria = compiler.criteria(request);

        assertThat(containsCondition(criteria, "code", "%a\\%b\\_c\\\\d%")).isTrue();
        assertThat(containsCondition(criteria, "title", "%a\\%b\\_c\\\\d%")).isTrue();
    }

    @Test
    void shouldUseFieldDefaultOperatorAndNormalizeValuesByFieldType() {
        QueryCompiler compiler = new QueryCompiler(descriptor());
        QueryRequest request = new QueryRequest(
                List.of(
                        new QueryCondition("code", null, List.of("A001"), null),
                        new QueryCondition("enabled", QueryOperator.EQ, List.of("true"), null)
                ),
                null,
                Map.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                List.of(),
                false,
                null
        );

        Criteria criteria = compiler.criteria(request);

        assertThat(containsCondition(criteria, "code", "A001")).isTrue();
        assertThat(containsCondition(criteria, "enabled", Boolean.TRUE)).isTrue();
    }

    @Test
    void shouldCompileInstantLocalDateRangeWithBusinessTimeZoneAsHalfOpenRange() {
        QueryCompiler compiler = new QueryCompiler(descriptor());
        QueryRequest request = new QueryRequest(
                List.of(new QueryCondition("createdAt", QueryOperator.BETWEEN,
                        List.of("2026-06-26", "2026-06-26"), "Asia/Shanghai")),
                null,
                Map.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                List.of(),
                false,
                null
        );

        Criteria criteria = compiler.criteria(request);

        assertThat(containsCondition(criteria, "createdAt", Instant.parse("2026-06-25T16:00:00Z"))).isTrue();
        assertThat(containsCondition(criteria, "createdAt", Instant.parse("2026-06-26T16:00:00Z"))).isTrue();
    }

    @Test
    void shouldCompileLocalDateTimeRangeWithoutUtcInstantCoercion() {
        QueryCompiler compiler = new QueryCompiler(descriptor());
        QueryRequest request = new QueryRequest(
                List.of(new QueryCondition("effectiveFrom", QueryOperator.BETWEEN,
                        List.of("2026-06-26T09:00:00", "2026-06-26T18:00:00"), null)),
                null,
                Map.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                List.of(),
                false,
                null
        );

        Criteria criteria = compiler.criteria(request);

        assertThat(containsCondition(criteria, "effectiveFrom",
                LocalDateTime.parse("2026-06-26T09:00:00"))).isTrue();
        assertThat(containsCondition(criteria, "effectiveFrom",
                LocalDateTime.parse("2026-06-26T18:00:00"))).isTrue();
    }

    @Test
    void shouldCompileCollectionCriteriaOperators() {
        QueryCompiler compiler = new QueryCompiler(descriptor());
        QueryRequest request = new QueryRequest(
                List.of(
                        new QueryCondition("tags", QueryOperator.CONTAINS, List.of("vip"), null),
                        new QueryCondition("tags", QueryOperator.CONTAINS_ANY, List.of("vip", "trial"), null),
                        new QueryCondition("tags", QueryOperator.CONTAINS_ALL, List.of("vip", "paid"), null),
                        new QueryCondition("tags", QueryOperator.EMPTY, List.of(), null),
                        new QueryCondition("tags", QueryOperator.NOT_EMPTY, List.of(), null)
                ),
                null,
                Map.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                List.of(),
                false,
                null
        );

        Criteria criteria = compiler.criteria(request);

        assertThat(clauses(criteria)).extracting(CriteriaClause::getOperator)
                .containsExactly(
                        net.ximatai.muyun.database.core.orm.CriteriaOperator.CONTAINS,
                        net.ximatai.muyun.database.core.orm.CriteriaOperator.CONTAINS_ANY,
                        net.ximatai.muyun.database.core.orm.CriteriaOperator.CONTAINS_ALL,
                        net.ximatai.muyun.database.core.orm.CriteriaOperator.IS_EMPTY,
                        net.ximatai.muyun.database.core.orm.CriteriaOperator.IS_NOT_EMPTY
                );
    }

    @Test
    void shouldRejectUndeclaredFieldsAndUnsupportedSorts() {
        QueryCompiler compiler = new QueryCompiler(descriptor());

        assertThatThrownBy(() -> compiler.criteria(new QueryRequest(
                List.of(new QueryCondition("secret", QueryOperator.EQ, List.of("x"), null)),
                null,
                Map.of(),
                List.of(),
                null,
                null,
                Map.of(),
                null,
                List.of(),
                false,
                null
        ))).hasMessage("query field is not supported by test.employee: secret");

        assertThatThrownBy(() -> compiler.sorts(new QueryRequest(
                List.of(),
                null,
                Map.of(),
                List.of(new QuerySort("title", false)),
                null,
                null,
                Map.of(),
                null,
                List.of(),
                false,
                null
        ))).hasMessage("query sort is not supported by test.employee: title");
    }

    @Test
    void shouldCompileNestedCriteriaTreeWithSameJoinSemanticsAsDynamicQuery() {
        QueryCriteria nested = QueryCriteria.group(
                QueryGroupOperator.OR,
                List.of(
                        new QueryCondition("ownerId", QueryOperator.EQ, List.of("u-1"), null),
                        new QueryCondition("ownerId", QueryOperator.EQ, List.of("u-2"), null)
        ));
        QueryCriteria root = QueryCriteria.group(
                QueryGroupOperator.OR,
                List.of(new QueryCondition("code", QueryOperator.EQ, List.of("C-001"), null), QueryCriteria.group(
                        QueryGroupOperator.AND,
                        List.of(new QueryCondition("status", QueryOperator.EQ, List.of("active"), null), nested)
                ))
        );

        Criteria criteria = QueryCompiler.compileCriteriaTree(root, QueryCompilerTest::compileSingle);

        List<CriteriaGroup.Entry> rootEntries = criteria.getRoot().getEntries();
        assertThat(rootEntries).hasSize(2);
        assertThat(join(rootEntries.get(0))).isEqualTo("AND");
        assertThat(join(rootEntries.get(1))).isEqualTo("OR");
        CriteriaGroup andGroup = (CriteriaGroup) node(rootEntries.get(1));
        assertThat(andGroup.getEntries()).hasSize(2);
        assertThat(join(andGroup.getEntries().get(0))).isEqualTo("AND");
        assertThat(join(andGroup.getEntries().get(1))).isEqualTo("AND");
        CriteriaGroup nestedOr = (CriteriaGroup) node(andGroup.getEntries().get(1));
        assertThat(nestedOr.getEntries()).hasSize(2);
        assertThat(join(nestedOr.getEntries().get(0))).isEqualTo("AND");
        assertThat(join(nestedOr.getEntries().get(1))).isEqualTo("OR");
    }

    @Test
    void shouldRejectCriteriaOutsideTheDeclaredCompositionMode() {
        QueryCriteria nested = QueryCriteria.group(QueryGroupOperator.AND, List.of(
                QueryCriteria.group(QueryGroupOperator.OR, List.of(
                        new QueryCondition("code", QueryOperator.EQ, List.of("C-001"), null)
                ))
        ));
        QueryRequest request = new QueryRequest(List.of(), nested, Map.of(), List.of(), null, null,
                Map.of(), null, List.of(), false, null);

        assertThatThrownBy(() -> new QueryCompiler(descriptor(), QueryCriteriaComposition.FLAT_AND)
                .criteria(request)).hasMessage("query criteria must contain only field conditions: $.children[0]");
        assertThatThrownBy(() -> new QueryCompiler(descriptor(), QueryCriteriaComposition.NONE)
                .criteria(request)).hasMessage("query criteria are not supported by this query surface");
    }

    @Test
    void shouldRejectCriteriaBeyondSharedComplexityLimits() {
        QueryCriteria tooDeep = nestedCriteria(QueryCriteria.MAXIMUM_DEPTH + 1);
        QueryCriteria tooManyValues = QueryCriteria.group(QueryGroupOperator.AND, List.of(
                new QueryCondition("tags", QueryOperator.CONTAINS_ANY,
                        java.util.stream.IntStream.range(0, QueryCriteria.MAXIMUM_COLLECTION_VALUES + 1)
                                .mapToObj(String::valueOf).map(value -> (Object) value).toList(), null)
        ));
        QueryCriteria tooManyNodes = QueryCriteria.group(QueryGroupOperator.AND,
                java.util.stream.IntStream.range(0, QueryCriteria.MAXIMUM_NODES)
                        .mapToObj(index -> new QueryCondition("code", QueryOperator.EQ,
                                List.of("C-" + index), null))
                        .map(node -> (QueryCriteriaNode) node).toList());

        assertThatThrownBy(() -> QueryCriteria.validate(tooDeep)).hasMessageContaining("maximum depth");
        assertThatThrownBy(() -> QueryCriteria.validate(tooManyValues)).hasMessageContaining("maximum collection values");
        assertThatThrownBy(() -> QueryCriteria.validate(tooManyNodes)).hasMessageContaining("maximum nodes");
        assertThatThrownBy(() -> QueryCompiler.compileCriteriaTree(tooDeep, QueryCompilerTest::compileSingle))
                .hasMessageContaining("maximum depth");
        assertThatThrownBy(() -> new QueryCompiler(descriptor()).criteria(new QueryRequest(
                java.util.stream.IntStream.range(0, QueryCriteria.MAXIMUM_NODES)
                        .mapToObj(index -> new QueryCondition("code", QueryOperator.EQ,
                                List.of("C-" + index), null)).toList(),
                null, Map.of(), List.of(), null, null, Map.of(), null, List.of(), false, null)))
                .hasMessageContaining("maximum nodes");
    }

    private static QueryCriteria nestedCriteria(int depth) {
        QueryCriteria result = QueryCriteria.group(QueryGroupOperator.AND, List.of(
                new QueryCondition("code", QueryOperator.EQ, List.of("C-001"), null)
        ));
        for (int index = 1; index < depth; index++) {
            result = QueryCriteria.group(QueryGroupOperator.AND, List.of(result));
        }
        return result;
    }

    private static QueryDescriptor descriptor() {
        return QueryDescriptor.builder("test.employee")
                .field(QueryField.of("enabled", QueryValueType.BOOLEAN, QueryOperator.EQ))
                .field(QueryField.of("code", QueryOperator.EQ, QueryOperator.LIKE)
                        .withQuickSearch().withSortable())
                .field(QueryField.of("title", QueryOperator.EQ, QueryOperator.LIKE)
                        .withQuickSearch())
                .field(QueryField.of("createdAt", QueryValueType.INSTANT, QueryOperator.BETWEEN))
                .field(QueryField.of("effectiveFrom", QueryValueType.DATETIME, QueryOperator.BETWEEN))
                .field(QueryField.of("tags", QueryValueType.JSON, QueryOperator.CONTAINS,
                        QueryOperator.CONTAINS_ANY, QueryOperator.CONTAINS_ALL,
                        QueryOperator.EMPTY, QueryOperator.NOT_EMPTY))
                .externalCriteria("scope", value -> Criteria.of().eq("departmentId", value))
                .defaultSort(Sort.asc("code"))
                .build();
    }

    private static Criteria compileSingle(QueryCondition condition) {
        return Criteria.of().eq(condition.fieldName(), condition.values().getFirst());
    }

    private boolean containsCondition(Criteria criteria, String fieldName, Object value) {
        return clauses(criteria).stream()
                .anyMatch(clause -> fieldName.equals(clause.getField())
                        && clause.getValues().contains(value));
    }

    private List<CriteriaClause> clauses(Criteria criteria) {
        List<CriteriaClause> result = new ArrayList<>();
        collect(criteria.getRoot(), result);
        return result;
    }

    private void collect(CriteriaGroup group, List<CriteriaClause> result) {
        for (CriteriaGroup.Entry entry : group.getEntries()) {
            Object node = node(entry);
            if (node instanceof CriteriaClause clause) {
                result.add(clause);
            } else if (node instanceof CriteriaGroup childGroup) {
                collect(childGroup, result);
            }
        }
    }

    private static Object node(CriteriaGroup.Entry entry) {
        try {
            Method method = entry.getClass().getMethod("getNode");
            return method.invoke(entry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read criteria node", e);
        }
    }

    private static String join(CriteriaGroup.Entry entry) {
        try {
            Method method = entry.getClass().getMethod("getJoin");
            return String.valueOf(method.invoke(entry));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read criteria join", e);
        }
    }
}
