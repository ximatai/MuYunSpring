package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.database.core.orm.SortDirection;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebQueryCondition;
import net.ximatai.muyun.spring.web.WebQueryCriteria;
import net.ximatai.muyun.spring.web.WebQueryGroupOperator;
import net.ximatai.muyun.spring.web.WebSort;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicQueryCondition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicWebQueryMapperTest {
    @Test
    void shouldMapCommonWebQueryConditionAndKeepDefaultOperatorWhenOmitted() {
        var conditions = DynamicWebQueryMapper.queryConditions(List.of(
                new WebQueryCondition("code", null, List.of("C-001")),
                new WebQueryCondition("amount", "GT", List.of(10)),
                new WebQueryCondition("occurredAt", "BETWEEN", List.of("2026-06-17", "2026-06-18"),
                        " Asia/Shanghai ")
        ));

        assertThat(conditions).hasSize(3);
        assertThat(conditions.get(0).fieldName()).isEqualTo("code");
        assertThat(conditions.get(0).operator()).isNull();
        assertThat(conditions.get(0).values()).isEqualTo(List.of("C-001"));
        assertThat(conditions.get(1).operator()).isEqualTo(DynamicQueryOperator.GT);
        assertThat(conditions.get(2).timeZone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void shouldMapCollectionWebQueryOperators() {
        var conditions = DynamicWebQueryMapper.queryConditions(List.of(
                new WebQueryCondition("tags", "CONTAINS", List.of("vip")),
                new WebQueryCondition("tags", "CONTAINS_ANY", List.of("vip", "trial")),
                new WebQueryCondition("tags", "CONTAINS_ALL", List.of("vip", "paid")),
                new WebQueryCondition("tags", "EMPTY", List.of()),
                new WebQueryCondition("tags", "NOT_EMPTY", List.of())
        ));

        assertThat(conditions).extracting(DynamicQueryCondition::operator)
                .containsExactly(DynamicQueryOperator.CONTAINS, DynamicQueryOperator.CONTAINS_ANY,
                        DynamicQueryOperator.CONTAINS_ALL, DynamicQueryOperator.EMPTY,
                        DynamicQueryOperator.NOT_EMPTY);
    }

    @Test
    void shouldNormalizeDynamicPageAndSorts() {
        var page = DynamicWebQueryMapper.page(new WebPageRequest(0, 999));
        var sorts = DynamicWebQueryMapper.sorts(List.of(
                new WebSort("amount", true),
                new WebSort("code", false)
        ));

        assertThat(page.getOffset()).isZero();
        assertThat(page.getLimit()).isEqualTo(500);
        assertThat(sorts[0].getField()).isEqualTo("amount");
        assertThat(sorts[0].getDirection()).isEqualTo(SortDirection.DESC);
        assertThat(sorts[1].getDirection()).isEqualTo(SortDirection.ASC);
    }

    @Test
    void shouldCompileArbitraryNestedQueryCriteriaTree() {
        WebQueryCriteria nested = WebQueryCriteria.group(
                WebQueryGroupOperator.OR,
                List.of(
                        new WebQueryCondition("ownerId", "EQ", List.of("u-1")),
                        new WebQueryCondition("ownerId", "EQ", List.of("u-2"))
        ));
        WebQueryCriteria root = WebQueryCriteria.group(
                WebQueryGroupOperator.OR,
                List.of(new WebQueryCondition("code", "EQ", List.of("C-001")), WebQueryCriteria.group(
                        WebQueryGroupOperator.AND,
                        List.of(new WebQueryCondition("status", "EQ", List.of("active")), nested)
                ))
        );

        Criteria criteria = DynamicWebQueryMapper.queryCriteria(root, DynamicWebQueryMapperTest::compileSingle);

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

    private static Criteria compileSingle(List<DynamicQueryCondition> conditions) {
        Criteria criteria = Criteria.of();
        for (DynamicQueryCondition condition : conditions) {
            criteria.eq(condition.fieldName(), condition.values().getFirst());
        }
        return criteria;
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
