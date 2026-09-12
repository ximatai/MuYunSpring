package net.ximatai.muyun.spring.ability.query;

/** The explicit criteria structure a query surface accepts. */
public enum QueryCriteriaComposition {
    /** The query surface does not accept an explicit criteria tree. */
    NONE,
    /** The query surface accepts one root AND group containing only field conditions. */
    FLAT_AND,
    /** The query surface accepts nested AND/OR criteria groups. */
    TREE
}
