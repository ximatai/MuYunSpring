package net.ximatai.muyun.spring.web;

/** A node in the ordered recursive criteria payload. */
public sealed interface WebQueryCriteriaNode permits WebQueryCriteria, WebQueryCondition {
    WebQueryCriteriaNodeKind kind();
}
