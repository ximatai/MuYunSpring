package net.ximatai.muyun.spring.platform.web;

import java.util.Collection;
import java.util.Map;

/** Resolves the visible labels of already-aggregated group values in one batch. */
@FunctionalInterface
public interface ListQuerySummaryGroupLabelResolver {
    Map<String, String> labels(String moduleAlias, ResolvedPageListQuerySummaryDescriptor summary,
                               Collection<Object> values);
}
