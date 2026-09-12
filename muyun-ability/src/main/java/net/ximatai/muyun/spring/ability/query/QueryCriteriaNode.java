package net.ximatai.muyun.spring.ability.query;

/** A node in an ordered, recursive query criteria tree. */
public sealed interface QueryCriteriaNode permits QueryCriteria, QueryCondition {
}
