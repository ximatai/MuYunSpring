package net.ximatai.muyun.spring.web;

/** Sort command for a navigator reference tree, including its resolved query scope. */
public record NavigatorReferenceTreeSortRequest(TreeSortWebRequest sort,
                                                WebQueryRequest query) {
}
