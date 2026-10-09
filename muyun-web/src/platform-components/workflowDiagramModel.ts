import { graphlib, layout } from '@dagrejs/dagre';
import type { WorkflowNode, WorkflowRoute } from '@muyun/web-contracts';
import { workflowTitle } from './workflowPresentation';

export interface WorkflowDiagramPosition {
  x: number;
  y: number;
}
export interface WorkflowDiagramLayout {
  version: 1;
  topology: string;
  nodes: Record<string, WorkflowDiagramPosition>;
}
export interface WorkflowDiagramNode {
  key: string;
  title: string;
  subtitle: string;
  type: string;
  status?: string;
  x: number;
  y: number;
  width: number;
  height: number;
}
export interface WorkflowDiagramRoute {
  key: string;
  source: string;
  target: string;
  title: string;
  status?: string;
  vertices?: WorkflowDiagramPosition[];
}

function object(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}
function jsonObject(text?: string): Record<string, unknown> | undefined {
  try {
    return object(JSON.parse(text ?? 'null'));
  } catch {
    return undefined;
  }
}
function topology(keys: string[], routes: WorkflowRoute[]): string {
  return JSON.stringify([
    [...keys].sort(),
    routes
      .map((route) => [route.routeKey, route.sourceNodeKey, route.targetNodeKey])
      .sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b))),
  ]);
}
function positions(signature: string, text?: string): Record<string, WorkflowDiagramPosition> {
  const data = jsonObject(text);
  if (data?.version !== 1 || data.topology !== signature) return {};
  return Object.fromEntries(
    Object.entries(object(data.nodes) ?? {}).flatMap(([key, value]) => {
      const point = object(value);
      return point &&
        typeof point.x === 'number' &&
        typeof point.y === 'number' &&
        Number.isFinite(point.x) &&
        Number.isFinite(point.y) &&
        Math.abs(point.x) <= 100000 &&
        Math.abs(point.y) <= 100000
        ? [[key, { x: point.x, y: point.y }]]
        : [];
    }),
  );
}

/** Only frozen semantic facts decorate runtime records; presentation never evaluates conditions. */
export function createWorkflowDiagramModel(
  nodes: WorkflowNode[],
  routes: WorkflowRoute[],
  layoutJson?: string,
  semanticJson?: string,
): { nodes: WorkflowDiagramNode[]; routes: WorkflowDiagramRoute[] } {
  const semantic = jsonObject(semanticJson);
  const definitions = new Map(
    (Array.isArray(semantic?.nodes) ? semantic.nodes : []).flatMap((value) => {
      const item = object(value);
      return item && typeof item.nodeKey === 'string' ? [[item.nodeKey, item] as const] : [];
    }),
  );
  const links = new Map(
    (Array.isArray(semantic?.links) ? semantic.links : []).flatMap((value) => {
      const item = object(value);
      return item && typeof item.routeKey === 'string' ? [[item.routeKey, item] as const] : [];
    }),
  );
  const cards: WorkflowDiagramNode[] = nodes.map((node) => {
    const definition = definitions.get(node.nodeKey);
    const compact = ['start', 'end'].includes(node.nodeType);
    const detail = node.nodeStatus
      ? node.nodeStatus === 'waiting' && node.nodeType !== 'converge'
        ? '未到达'
        : workflowTitle(node.nodeStatus)
      : node.nodeType === 'branch'
        ? (node.routeMode ?? definition?.routeMode) === 'manual'
          ? '人工单选'
          : '条件分流'
        : node.nodeType === 'converge'
          ? ({ all: '全部到达', any: '任一到达', ratio: '比例到达' } as Record<string, string>)[
              String(node.convergeMode ?? definition?.convergeMode ?? 'all')
            ]
          : node.approvalMode
            ? workflowTitle(node.approvalMode)
            : '';
    return {
      key: node.nodeKey,
      title: node.nodeTitle ?? node.title ?? node.nodeKey,
      subtitle: [workflowTitle(node.nodeType), detail].filter(Boolean).join(' · '),
      type: node.nodeType,
      status: node.nodeStatus,
      x: 0,
      y: 0,
      width: compact ? 176 : 224,
      height: compact ? 64 : 88,
    };
  });
  const keys = new Set(cards.map((node) => node.key));
  const edges: WorkflowDiagramRoute[] = routes
    .filter((route) => keys.has(route.sourceNodeKey) && keys.has(route.targetNodeKey))
    .map((route) => {
      const definition = links.get(route.routeKey);
      const name = route.title ?? definition?.title;
      const fallback = route.defaultRoute ?? definition?.defaultRoute;
      return {
        key: route.routeKey,
        source: route.sourceNodeKey,
        target: route.targetNodeKey,
        title: typeof name === 'string' && name ? name : fallback ? '默认出口' : '',
        status: route.routeStatus,
      };
    });
  const graph = new graphlib.Graph({ multigraph: true });
  graph.setGraph({ rankdir: 'TB', nodesep: 64, ranksep: 64, marginx: 32, marginy: 32 });
  cards.forEach((node) => graph.setNode(node.key, { width: node.width, height: node.height }));
  edges.forEach((edge) => graph.setEdge(edge.source, edge.target, {}, edge.key));
  layout(graph);
  const stored = positions(
    topology(
      nodes.map((node) => node.nodeKey),
      routes,
    ),
    layoutJson,
  );
  const moved = cards.some((node) => {
    if (!Object.hasOwn(stored, node.key)) return false;
    const point = graph.node(node.key);
    return (
      stored[node.key]!.x !== point.x - node.width / 2 || stored[node.key]!.y !== point.y - node.height / 2
    );
  });
  return {
    nodes: cards.map((node) => {
      const point = graph.node(node.key);
      return {
        ...node,
        ...(Object.hasOwn(stored, node.key)
          ? stored[node.key]
          : { x: point.x - node.width / 2, y: point.y - node.height / 2 }),
      };
    }),
    routes: edges.map((edge) => ({
      ...edge,
      vertices: moved
        ? []
        : graph.edge({ v: edge.source, w: edge.target, name: edge.key }).points.slice(1, -1),
    })),
  };
}

/** Store only positions keyed by business identity, never X6 cells, viewport or business properties. */
export function serializeWorkflowDiagramLayout(
  nodes: Array<{ key: string; x: number; y: number }>,
  routes: WorkflowRoute[],
): string {
  const result: WorkflowDiagramLayout = {
    version: 1,
    topology: topology(
      nodes.map((node) => node.key),
      routes,
    ),
    nodes: Object.fromEntries(
      nodes.map((node) => [node.key, { x: Math.round(node.x), y: Math.round(node.y) }]),
    ),
  };
  return JSON.stringify(result);
}
