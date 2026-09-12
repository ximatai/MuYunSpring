package net.ximatai.muyun.spring.ability.query;

import java.util.List;
import java.util.Collection;

public record QueryCriteria(QueryGroupOperator operator,
                            List<QueryCriteriaNode> children) implements QueryCriteriaNode {
    public static final int MAXIMUM_DEPTH = 4;
    public static final int MAXIMUM_NODES = 50;
    public static final int MAXIMUM_COLLECTION_VALUES = 100;

    public QueryCriteria {
        operator = operator == null ? QueryGroupOperator.AND : operator;
        children = children == null ? List.of() : List.copyOf(children);
        if (children.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("query criteria children must not contain null");
        }
    }

    public boolean isEmpty() {
        return children.isEmpty();
    }

    public static QueryCriteria group(QueryGroupOperator operator, List<? extends QueryCriteriaNode> children) {
        return new QueryCriteria(operator, children == null ? List.of() : List.copyOf(children));
    }

    /** Validates source-neutral complexity limits before a criteria tree is compiled. */
    public static void validate(QueryCriteria criteria) {
        validate(criteria, QueryCriteriaComposition.TREE);
    }

    /** Applies the same limits to the compatibility top-level AND shorthand. */
    public static void validateConditions(Collection<QueryCondition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return;
        }
        validate(group(QueryGroupOperator.AND, List.copyOf(conditions)));
    }

    /** Validates both common complexity limits and one query surface's composition mode. */
    public static void validate(QueryCriteria criteria, QueryCriteriaComposition composition) {
        QueryCriteriaComposition resolvedComposition = composition == null
                ? QueryCriteriaComposition.TREE : composition;
        if (criteria == null || criteria.isEmpty()) {
            return;
        }
        if (resolvedComposition == QueryCriteriaComposition.NONE) {
            throw new IllegalArgumentException("query criteria are not supported by this query surface");
        }
        if (resolvedComposition == QueryCriteriaComposition.FLAT_AND) {
            validateFlatAnd(criteria);
        }
        Limits limits = new Limits();
        validateGroup(criteria, 1, "$", limits);
    }

    private static void validateFlatAnd(QueryCriteria criteria) {
        if (criteria.operator() != QueryGroupOperator.AND) {
            throw new IllegalArgumentException("query criteria must use a root AND group");
        }
        for (int index = 0; index < criteria.children().size(); index++) {
            if (!(criteria.children().get(index) instanceof QueryCondition)) {
                throw new IllegalArgumentException("query criteria must contain only field conditions: $.children["
                        + index + "]");
            }
        }
    }

    private static void validateGroup(QueryCriteria group, int depth, String path, Limits limits) {
        if (depth > MAXIMUM_DEPTH) {
            throw new IllegalArgumentException("query criteria exceeds maximum depth of " + MAXIMUM_DEPTH
                    + ": " + path);
        }
        countNode(limits, path);
        for (int index = 0; index < group.children().size(); index++) {
            QueryCriteriaNode child = group.children().get(index);
            String childPath = path + ".children[" + index + "]";
            if (child instanceof QueryCriteria childGroup) {
                validateGroup(childGroup, depth + 1, childPath, limits);
            } else if (child instanceof QueryCondition condition) {
                countNode(limits, childPath);
                if (condition.values().size() > MAXIMUM_COLLECTION_VALUES) {
                    throw new IllegalArgumentException("query criterion exceeds maximum collection values of "
                            + MAXIMUM_COLLECTION_VALUES + ": " + childPath);
                }
            } else {
                throw new IllegalArgumentException("query criteria child is not supported: " + childPath);
            }
        }
    }

    private static void countNode(Limits limits, String path) {
        limits.nodes++;
        if (limits.nodes > MAXIMUM_NODES) {
            throw new IllegalArgumentException("query criteria exceeds maximum nodes of " + MAXIMUM_NODES
                    + ": " + path);
        }
    }

    private static final class Limits {
        private int nodes;
    }
}
