import type { WorkflowDesign, WorkflowNode, WorkflowRoute } from '@muyun/web-contracts';

export function createWorkflowApprovalNode(nodeKey: string, title: string): WorkflowNode {
  return {
    nodeKey,
    nodeType: 'approval',
    title,
    approvalMode: 'all',
    participantPolicyText: '{"rules":[]}',
    allowReject: true,
  };
}

/** A branch and its convergence are one editable business structure. */
export function insertWorkflowBranch(design: WorkflowDesign, routeKey: string, key: string): WorkflowDesign {
  const route = design.links.find((item) => item.routeKey === routeKey);
  if (!route) throw new Error('请选择需要插入分支的路径');
  const join = `${key}_join`;
  const nodes: WorkflowNode[] = [
    { nodeKey: key, nodeType: 'branch', title: '条件分支', routeMode: 'auto', convergeNodeKey: join },
    createWorkflowApprovalNode(`${key}_path1`, '分支审批1'),
    createWorkflowApprovalNode(`${key}_path2`, '分支审批2'),
    { nodeKey: join, nodeType: 'converge', title: '分支汇聚', convergeMode: 'all' },
  ];
  if (nodes.some((node) => design.nodes.some((existing) => existing.nodeKey === node.nodeKey)))
    throw new Error('节点标识已存在');
  const links: WorkflowRoute[] = [
    ...[1, 2].flatMap((index) => [
      {
        routeKey: `${key}_exit${index}`,
        sourceNodeKey: key,
        targetNodeKey: `${key}_path${index}`,
        title: `路径${index}`,
      },
      {
        routeKey: `${key}_return${index}`,
        sourceNodeKey: `${key}_path${index}`,
        targetNodeKey: join,
        title: '汇聚',
      },
    ]),
    { routeKey: `${key}_next`, sourceNodeKey: join, targetNodeKey: route.targetNodeKey, title: '继续' },
  ];
  return {
    ...design,
    nodes: [...design.nodes, ...nodes],
    links: [
      ...design.links.map((item) => (item === route ? { ...item, targetNodeKey: key } : item)),
      ...links,
    ],
  };
}

export function appendWorkflowBranchPath(
  design: WorkflowDesign,
  branchKey: string,
  key: string,
): WorkflowDesign {
  const branch = design.nodes.find((node) => node.nodeKey === branchKey && node.nodeType === 'branch');
  const join = design.nodes.find(
    (node) => node.nodeKey === branch?.convergeNodeKey && node.nodeType === 'converge',
  );
  if (!branch || !join) throw new Error('分支必须绑定有效汇聚节点');
  if (design.nodes.some((node) => node.nodeKey === key)) throw new Error('节点标识已存在');
  const index = design.links.filter((link) => link.sourceNodeKey === branchKey).length + 1;
  return {
    ...design,
    nodes: [...design.nodes, createWorkflowApprovalNode(key, `分支审批${index}`)],
    links: [
      ...design.links,
      { routeKey: `${key}_in`, sourceNodeKey: branchKey, targetNodeKey: key, title: `路径${index}` },
      { routeKey: `${key}_out`, sourceNodeKey: key, targetNodeKey: join.nodeKey, title: '汇聚' },
    ],
  };
}

export function removeWorkflowBranch(design: WorkflowDesign, branchKey: string): WorkflowDesign {
  const branch = design.nodes.find((node) => node.nodeKey === branchKey && node.nodeType === 'branch');
  const join = design.nodes.find(
    (node) => node.nodeKey === branch?.convergeNodeKey && node.nodeType === 'converge',
  );
  if (!branch || !join) throw new Error('分支必须绑定有效汇聚节点');
  const domain = new Set<string>([branchKey]);
  function collect(key: string, visiting = new Set<string>()) {
    if (key === join!.nodeKey) return;
    if (visiting.has(key)) throw new Error('分支内存在循环，请先修正路径');
    const outgoing = design.links.filter((link) => link.sourceNodeKey === key);
    if (!outgoing.length) throw new Error('分支路径未到达配对汇聚节点');
    domain.add(key);
    outgoing.forEach((link) => collect(link.targetNodeKey, new Set([...visiting, key])));
  }
  collect(branchKey);
  domain.add(join.nodeKey);
  const incoming = design.links.filter(
    (link) => !domain.has(link.sourceNodeKey) && domain.has(link.targetNodeKey),
  );
  const outgoing = design.links.filter(
    (link) => domain.has(link.sourceNodeKey) && !domain.has(link.targetNodeKey),
  );
  if (
    incoming.length !== 1 ||
    incoming[0]?.targetNodeKey !== branchKey ||
    outgoing.length !== 1 ||
    outgoing[0]?.sourceNodeKey !== join.nodeKey
  )
    throw new Error('分支包含外部交叉路径，不能整体删除');
  return {
    ...design,
    nodes: design.nodes.filter((node) => !domain.has(node.nodeKey)),
    links: design.links
      .filter(
        (link) =>
          !domain.has(link.sourceNodeKey) && (!domain.has(link.targetNodeKey) || link === incoming[0]),
      )
      .map((link) => (link === incoming[0] ? { ...link, targetNodeKey: outgoing[0]!.targetNodeKey } : link)),
  };
}

export function updateWorkflowRoute(
  design: WorkflowDesign,
  routeKey: string,
  patch: Partial<WorkflowRoute>,
): WorkflowDesign {
  const route = design.links.find((link) => link.routeKey === routeKey);
  if (!route) throw new Error('路径不存在');
  return {
    ...design,
    links: design.links.map((link) => {
      if (link === route)
        return {
          ...link,
          ...patch,
          ...(patch.defaultRoute === true ? { conditionExpression: undefined } : {}),
        };
      return patch.defaultRoute === true && link.sourceNodeKey === route.sourceNodeKey
        ? { ...link, defaultRoute: false }
        : link;
    }),
  };
}

/** Edits the semantic graph; coordinates never decide workflow structure. */
export function insertWorkflowNode(
  design: WorkflowDesign,
  routeKey: string,
  node: WorkflowNode,
): WorkflowDesign {
  const route = design.links.find((item) => item.routeKey === routeKey);
  if (!route) throw new Error('请选择需要插入节点的路径');
  if (design.nodes.some((item) => item.nodeKey === node.nodeKey)) throw new Error('节点标识已存在');
  return {
    ...design,
    nodes: [...design.nodes, node],
    links: [
      ...design.links.map((item) => (item === route ? { ...item, targetNodeKey: node.nodeKey } : item)),
      {
        routeKey: `${node.nodeKey}_next`,
        sourceNodeKey: node.nodeKey,
        targetNodeKey: route.targetNodeKey,
        title: '继续',
      },
    ],
  };
}

export function removeWorkflowNode(design: WorkflowDesign, nodeKey: string): WorkflowDesign {
  const node = design.nodes.find((item) => item.nodeKey === nodeKey);
  if (!node || ['start', 'end'].includes(node.nodeType)) throw new Error('开始和结束节点必须保留');
  const incoming = design.links.filter((item) => item.targetNodeKey === nodeKey);
  const outgoing = design.links.filter((item) => item.sourceNodeKey === nodeKey);
  if (incoming.length !== 1 || outgoing.length !== 1 || outgoing[0]?.conditionExpression)
    throw new Error('该节点包含分支或复杂路径，请先调整连线，确保删除后路径含义明确');
  return {
    ...design,
    nodes: design.nodes.filter((item) => item.nodeKey !== nodeKey),
    links: design.links
      .filter((item) => item.sourceNodeKey !== nodeKey)
      .map((item) =>
        item.targetNodeKey === nodeKey ? { ...item, targetNodeKey: outgoing[0]!.targetNodeKey } : item,
      ),
  };
}

export function createWorkflowDesign(approval: boolean): WorkflowDesign {
  const nodes: WorkflowNode[] = [
    { nodeKey: 'start', nodeType: 'start', title: '提交' },
    {
      ...createWorkflowApprovalNode('approval', '审批'),
      requireRejectReason: true,
      allowRejectReturnToMe: true,
      allowRollback: true,
      requireRollbackReason: true,
      allowAddSign: true,
    },
    ...(approval
      ? [
          {
            nodeKey: 'approved',
            nodeType: 'milestone',
            title: '审批完成',
            milestoneType: 'approval_completed',
          },
        ]
      : []),
    { nodeKey: 'end', nodeType: 'end', title: '完成' },
  ];
  return {
    nodes,
    links: nodes.slice(1).map((item, index) => ({
      routeKey: `route_${index + 1}`,
      sourceNodeKey: nodes[index]!.nodeKey,
      targetNodeKey: item.nodeKey,
      title: '继续',
    })),
  };
}
