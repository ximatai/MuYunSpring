import type { QueryCriteriaGroup, QueryCriteriaNode } from '@muyun/web-contracts';
import { QUERY_CRITERIA_MAXIMUM_DEPTH, QUERY_CRITERIA_MAXIMUM_NODES } from './queryCriteriaDraft';

export function criteriaLeafCount(criteria: QueryCriteriaGroup | undefined): number {
  return (
    criteria?.children.reduce(
      (count, child) => count + (child.kind === 'CONDITION' ? 1 : criteriaLeafCount(child)),
      0,
    ) ?? 0
  );
}

/** Normalize conjunction without adding a level to a standalone group or breaking OR semantics. */
export function conjoinQueryCriteria(
  nodes: readonly (QueryCriteriaNode | undefined)[],
): QueryCriteriaGroup | undefined {
  const children = nodes.flatMap((node) =>
    !node ? [] : node.kind === 'GROUP' && node.operator === 'AND' ? node.children : [node],
  );
  if (children.length === 1 && children[0]!.kind === 'GROUP') return children[0];
  return children.length ? { kind: 'GROUP', operator: 'AND', children } : undefined;
}

/** Same root=1, groups-and-conditions budget as the server's QueryCriteria.validate. */
export function queryCriteriaComplexity(
  criteria: QueryCriteriaGroup | undefined,
): 'depth' | 'nodes' | undefined {
  let count = 0;
  let tooDeep = false;
  function visit(node: QueryCriteriaNode, depth: number) {
    count++;
    if (node.kind === 'GROUP') {
      if (depth > QUERY_CRITERIA_MAXIMUM_DEPTH) tooDeep = true;
      node.children.forEach((child) => visit(child, child.kind === 'GROUP' ? depth + 1 : depth));
    }
  }
  if (criteria) visit(criteria, 1);
  return tooDeep ? 'depth' : count > QUERY_CRITERIA_MAXIMUM_NODES ? 'nodes' : undefined;
}

export function queryCriteriaComplexityMessage(
  criteria: QueryCriteriaGroup | undefined,
  language: import('@muyun/web-core').LanguageContext,
): string | undefined {
  const issue = queryCriteriaComplexity(criteria);
  return issue === 'depth'
    ? language.t(
        'platform.screen.QueryCriteriaComposer.at-most-value-group-levels-are-supported',
        '最多支持 {value1} 层括号组',
        { value1: QUERY_CRITERIA_MAXIMUM_DEPTH },
      )
    : issue === 'nodes'
      ? language.t(
          'platform.screen.QueryCriteriaComposer.at-most-value-conditions-and-groups-are-supported',
          '最多支持 {value1} 个条件和括号组',
          { value1: QUERY_CRITERIA_MAXIMUM_NODES },
        )
      : undefined;
}
