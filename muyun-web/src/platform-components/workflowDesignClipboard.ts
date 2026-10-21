import type { WorkflowDesign, WorkflowNode, WorkflowRoute } from '@muyun/web-contracts';
import { removeWorkflowBranch } from './workflowDesignEditing';

interface Fragment {
  kind: 'muyun.workflow.fragment';
  version: 1;
  nodes: WorkflowNode[];
  links: WorkflowRoute[];
  entry: string;
  exit: string;
}
const types = new Set(['approval', 'task', 'milestone', 'branch', 'converge']);
function validate(fragment: Fragment) {
  if (
    fragment?.kind !== 'muyun.workflow.fragment' ||
    fragment.version !== 1 ||
    !Array.isArray(fragment.nodes) ||
    !Array.isArray(fragment.links) ||
    !fragment.nodes.length ||
    fragment.nodes.length > 500 ||
    fragment.links.length > 2000
  )
    throw new Error('剪贴板中不是有效的流程片段');
  const keys = new Set(fragment.nodes.map((node) => node?.nodeKey));
  const routes = new Set(fragment.links.map((route) => route?.routeKey));
  if (
    keys.size !== fragment.nodes.length ||
    [...keys].some((key) => typeof key !== 'string' || !key) ||
    routes.size !== fragment.links.length ||
    [...routes].some((key) => typeof key !== 'string' || !key) ||
    fragment.nodes.some((node) => !types.has(node.nodeType)) ||
    !keys.has(fragment.entry) ||
    !keys.has(fragment.exit) ||
    fragment.links.some((link) => !keys.has(link.sourceNodeKey) || !keys.has(link.targetNodeKey))
  )
    throw new Error('流程片段包含无效节点或路径');
  for (const node of fragment.nodes) {
    if (
      node.nodeType === 'branch' &&
      !fragment.nodes.some((join) => join.nodeKey === node.convergeNodeKey && join.nodeType === 'converge')
    )
      throw new Error('请完整复制分支及其汇聚');
    if (
      node.nodeType === 'converge' &&
      !fragment.nodes.some(
        (branch) => branch.nodeType === 'branch' && branch.convergeNodeKey === node.nodeKey,
      )
    )
      throw new Error('请完整复制分支及其汇聚');
  }
  const visited = new Set<string>();
  function visit(key: string, path = new Set<string>()) {
    if (path.has(key)) throw new Error('流程片段不能包含循环');
    if (visited.has(key)) return;
    const outgoing = fragment.links.filter((link) => link.sourceNodeKey === key);
    if ((key === fragment.exit) !== (outgoing.length === 0))
      throw new Error('请选择具有单一入口和出口的连续流程片段');
    for (const link of outgoing) visit(link.targetNodeKey, new Set([...path, key]));
    visited.add(key);
  }
  if (fragment.links.some((link) => link.targetNodeKey === fragment.entry))
    throw new Error('流程片段入口无效');
  visit(fragment.entry);
  if (visited.size !== keys.size) throw new Error('请选择连续的流程片段');
  for (const branch of fragment.nodes.filter((node) => node.nodeType === 'branch')) {
    const domain = new Set<string>();
    function collect(key: string) {
      if (domain.has(key)) return;
      domain.add(key);
      if (key === branch.convergeNodeKey) return;
      const outgoing = fragment.links.filter((link) => link.sourceNodeKey === key);
      if (!outgoing.length) throw new Error('分支路径必须到达配对汇聚');
      outgoing.forEach((link) => collect(link.targetNodeKey));
    }
    collect(branch.nodeKey);
    if (
      fragment.links.some(
        (link) =>
          (!domain.has(link.sourceNodeKey) &&
            domain.has(link.targetNodeKey) &&
            link.targetNodeKey !== branch.nodeKey) ||
          (domain.has(link.sourceNodeKey) &&
            !domain.has(link.targetNodeKey) &&
            link.sourceNodeKey !== branch.convergeNodeKey),
      )
    )
      throw new Error('分支不能包含外部交叉路径');
  }
}

export function copyWorkflowFragment(design: WorkflowDesign, selected: string[]): string {
  const keys = new Set(
    selected.filter((key) => types.has(design.nodes.find((node) => node.nodeKey === key)?.nodeType ?? '')),
  );
  for (const key of [...keys]) {
    if (design.nodes.find((node) => node.nodeKey === key)?.nodeType !== 'branch') continue;
    const remaining = new Set(removeWorkflowBranch(design, key).nodes.map((node) => node.nodeKey));
    design.nodes.filter((node) => !remaining.has(node.nodeKey)).forEach((node) => keys.add(node.nodeKey));
  }
  const incoming = design.links.filter(
    (link) => !keys.has(link.sourceNodeKey) && keys.has(link.targetNodeKey),
  );
  const outgoing = design.links.filter(
    (link) => keys.has(link.sourceNodeKey) && !keys.has(link.targetNodeKey),
  );
  if (incoming.length !== 1 || outgoing.length !== 1)
    throw new Error('请选择具有单一入口和出口的连续节点；分支会整体复制');
  const fragment: Fragment = {
    kind: 'muyun.workflow.fragment',
    version: 1,
    nodes: design.nodes.filter((node) => keys.has(node.nodeKey)),
    links: design.links.filter((link) => keys.has(link.sourceNodeKey) && keys.has(link.targetNodeKey)),
    entry: incoming[0].targetNodeKey,
    exit: outgoing[0].sourceNodeKey,
  };
  validate(fragment);
  return JSON.stringify(fragment);
}

export function pasteWorkflowFragment(
  design: WorkflowDesign,
  routeKey: string,
  text: string,
  prefix: string,
): { design: WorkflowDesign; keys: string[]; reconfigureSelectorKeys: string[] } {
  if (text.length > 1_000_000) throw new Error('流程片段过大');
  let fragment: Fragment;
  try {
    fragment = JSON.parse(text);
  } catch {
    throw new Error('剪贴板中不是有效的流程片段');
  }
  validate(fragment);
  const route = design.links.find((link) => link.routeKey === routeKey);
  if (!route) throw new Error('请选择粘贴位置的路径或节点');
  const mapping = new Map(fragment.nodes.map((node, index) => [node.nodeKey, `${prefix}_n${index}`]));
  const linkKey = (index: number) => `${prefix}_r${index}`;
  if (
    design.nodes.some((node) => [...mapping.values()].includes(node.nodeKey)) ||
    design.links.some((link) =>
      Array.from({ length: fragment.links.length + 1 }, (_, i) => linkKey(i)).includes(link.routeKey),
    )
  )
    throw new Error('粘贴标识冲突，请重试');
  const nodes = fragment.nodes.map((node) => {
    const copy = { ...node };
    delete copy.id;
    delete copy.version;
    delete copy.nodeStatus;
    return {
      ...copy,
      nodeKey: mapping.get(node.nodeKey)!,
      convergeNodeKey: node.convergeNodeKey ? mapping.get(node.convergeNodeKey) : undefined,
      selectorNodeKey: node.selectorNodeKey ? mapping.get(node.selectorNodeKey) : undefined,
    };
  });
  const links = fragment.links.map((link, index) => {
    const copy = { ...link };
    delete copy.id;
    delete copy.routeStatus;
    delete copy.routeReason;
    delete copy.selectedReason;
    return {
      ...copy,
      routeKey: linkKey(index),
      sourceNodeKey: mapping.get(link.sourceNodeKey)!,
      targetNodeKey: mapping.get(link.targetNodeKey)!,
    };
  });
  return {
    design: {
      ...design,
      nodes: [...design.nodes, ...nodes],
      links: [
        ...design.links.map((link) =>
          link === route ? { ...link, targetNodeKey: mapping.get(fragment.entry)! } : link,
        ),
        ...links,
        {
          routeKey: linkKey(links.length),
          sourceNodeKey: mapping.get(fragment.exit)!,
          targetNodeKey: route.targetNodeKey,
          title: '继续',
        },
      ],
    },
    keys: nodes.map((node) => node.nodeKey),
    reconfigureSelectorKeys: nodes
      .filter((node) => node.nodeType === 'branch' && node.routeMode === 'manual' && !node.selectorNodeKey)
      .map((node) => node.nodeKey),
  };
}
