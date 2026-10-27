import { describe, expect, it } from 'vitest';
import type { WorkflowDesign } from '@muyun/web-contracts';
import { createWorkflowDesign } from '@/platform-components/workflowDesignEditing';
import {
  diagnoseWorkflowDraft,
  parseWorkflowDesignOperations,
  prepareWorkflowDesignOperations,
  workflowDesignChangeLines,
  workflowDesignOperationsSchema,
} from '@/platform-components/workflowDesignOperations';

const fields = [
  { name: 'reviewer', referenceModule: 'iam.user' },
  { name: 'amount', valueType: 'DECIMAL' },
];
const ref = (created: string, part: string) => ({ created, part });
const prepare = (design: WorkflowDesign, operations: unknown[]) =>
  prepareWorkflowDesignOperations(design, parseWorkflowDesignOperations({ operations }), fields);
const self = [{ type: 'INITIATOR_SELF' }];

it('uses one bounded grammar for strict parsing and the advertised schema', () => {
  const schema = workflowDesignOperationsSchema();
  expect(schema).toMatchObject({ type: 'object', additionalProperties: false, required: ['operations'] });
  expect((schema.properties as Record<string, unknown>).operations).toMatchObject({
    minItems: 1,
    maxItems: 40,
  });
  // Returned descriptors cannot change the trusted parser grammar.
  schema.additionalProperties = true;
  for (const input of [
    {},
    { operations: [] },
    { operations: [], layoutJson: '{}' },
    { operations: Array.from({ length: 41 }, () => ({ op: 'delete-node', node: 'approval' })) },
    { operations: [{ op: 'update-node', node: 'approval', patch: {} }] },
    { operations: [{ op: 'update-node', node: 'approval', patch: { title: ' '.repeat(5) } }] },
    { operations: [{ op: 'update-node', node: 'approval', patch: { title: '😀'.repeat(129) } }] },
    {
      operations: [
        { op: 'insert-node', as: '   ', route: 'route_1', node: { kind: 'approval', title: 'A' } },
      ],
    },
  ])
    expect(() => parseWorkflowDesignOperations(input)).toThrow();
  expect(() =>
    parseWorkflowDesignOperations({
      operations: [{ op: 'update-node', node: 'approval', patch: { title: '😀'.repeat(128) } }],
    }),
  ).not.toThrow();
});

it('creates serial approval, notice and milestone nodes and reliably references newly created parts', () => {
  const before = createWorkflowDesign(false);
  const snapshot = JSON.stringify(before);
  const result = prepare(before, [
    {
      op: 'insert-node',
      as: 'review',
      route: 'route_1',
      node: {
        kind: 'approval',
        title: '主管审批',
        approvalMode: 'ratio',
        approvalRatio: 60,
        participants: [{ type: 'RELATIVE', relation: 'SUPERVISOR', depth: 2 }],
      },
    },
    {
      op: 'insert-node',
      as: 'notice',
      route: ref('review', 'next'),
      node: { kind: 'notice', title: '通知提交人', participants: self },
    },
    {
      op: 'insert-node',
      as: 'done',
      route: ref('notice', 'next'),
      node: { kind: 'milestone', title: '审批完成' },
    },
    { op: 'update-node', node: ref('review', 'node'), patch: { title: '二级主管审批' } },
  ]);
  expect(JSON.stringify(before)).toBe(snapshot);
  expect(result.design.nodes.find((node) => node.nodeKey === result.created.review!.node)).toMatchObject({
    title: '二级主管审批',
    approvalMode: 'ratio',
    approvalRatio: 60,
  });
  expect(result.design.nodes.find((node) => node.nodeKey === result.created.notice!.node)).toMatchObject({
    nodeType: 'approval',
    approvalMode: 'notice',
  });
  expect(result.design.nodes.find((node) => node.nodeKey === result.created.done!.node)).toMatchObject({
    nodeType: 'milestone',
    milestoneType: 'approval_completed',
  });
  expect(
    result.design.links.find((route) => route.routeKey === result.created.review!.next)?.targetNodeKey,
  ).toBe(result.created.notice!.node);
  expect(result.changes).toEqual(workflowDesignChangeLines(before, result.design));
  expect(result.changes.length).toBeGreaterThan(4);
});

it('builds paired and nested branches, appends paths, sets formal modes, conditions and unique defaults', () => {
  const result = prepare(createWorkflowDesign(false), [
    { op: 'insert-branch', as: 'b', route: 'route_1', title: '金额分支' },
    { op: 'append-path', as: 'extra', branch: ref('b', 'branch') },
    {
      op: 'update-node',
      node: ref('extra', 'node'),
      patch: { participants: [{ type: 'FIELD', fieldName: 'reviewer' }] },
    },
    {
      op: 'update-branch',
      branch: ref('b', 'branch'),
      patch: {
        routeMode: 'manual',
        selectorNode: 'start',
        requireManualSelectionReason: true,
        convergeMode: 'ratio',
        convergeRatio: 70,
      },
    },
    {
      op: 'update-route',
      route: ref('b', 'exit1'),
      patch: { conditionExpression: '{amount} > 100', title: '大额' },
    },
    { op: 'update-route', route: ref('b', 'exit2'), patch: { defaultRoute: true } },
    { op: 'update-route', route: ref('extra', 'in'), patch: { defaultRoute: true } },
    { op: 'insert-branch', as: 'nested', route: ref('b', 'exit1') },
  ]);
  const b = result.created.b!;
  expect(result.design.nodes.find((node) => node.nodeKey === b.branch)).toMatchObject({
    title: '金额分支',
    routeMode: 'manual',
    selectorNodeKey: 'start',
    requireManualSelectionReason: true,
  });
  expect(result.design.nodes.find((node) => node.nodeKey === b.join)).toMatchObject({
    convergeMode: 'ratio',
    convergeRatio: 70,
  });
  expect(result.design.links.find((route) => route.routeKey === b.exit1)).toMatchObject({
    conditionExpression: '{amount} > 100',
    title: '大额',
  });
  expect(result.design.links.find((route) => route.routeKey === b.exit2)?.defaultRoute).toBe(false);
  expect(result.design.links.find((route) => route.routeKey === result.created.extra!.in)?.defaultRoute).toBe(
    true,
  );
  expect(result.diagnostics.warnings.some((item) => item.includes('公式'))).toBe(true);
  const removed = prepare(result.design, [{ op: 'delete-branch', branch: b.branch }]);
  expect(removed.design).toEqual(createWorkflowDesign(false));
});

it('publishes no partial design when a later operation is invalid', () => {
  const before = createWorkflowDesign(false);
  const snapshot = JSON.stringify(before);
  expect(() =>
    prepare(before, [
      { op: 'update-node', node: 'approval', patch: { title: 'Valid first change' } },
      { op: 'insert-branch', as: 'b', route: 'route_1' },
      {
        op: 'update-node',
        node: ref('b', 'path1'),
        patch: { participants: [{ type: 'FIELD', fieldName: 'amount' }] },
      },
    ]),
  ).toThrow('账号引用字段');
  expect(JSON.stringify(before)).toBe(snapshot);
  expect(() => prepare(before, [{ op: 'delete-node', node: ref('future', 'node') }])).toThrow('尚未创建');
  expect(() =>
    prepare(before, [
      { op: 'insert-node', as: 'a', route: 'route_1', node: { kind: 'approval', title: 'A' } },
      { op: 'delete-node', node: ref('a', 'unknown') },
    ]),
  ).toThrow('部件不存在');
});

it('preserves layout, persistent identities, unknown parameters, task configuration and untouched participants', () => {
  const before = createWorkflowDesign(false);
  before.layoutJson = '{"nodes":{"approval":{"x":42,"y":20}},"viewport":{"zoom":0.8}}';
  Object.assign(before.nodes[1]!, {
    id: 'stored',
    version: 8,
    nodeConfigText: '{"futureConfig":{"x":1}}',
    participantPolicyText: '{"rules":[{"type":"USER","ids":["existing-account"]}],"future":true}',
    warningDurationMinutes: 42,
    futureFlag: true,
  });
  const task = {
    nodeKey: 'task',
    nodeType: 'task',
    title: 'Task',
    nodeConfigText: '{"guides":[{"kind":"write"}],"checks":[1]}',
    taskDefinitionId: 'existing-task',
    participantPolicyText: '{"rules":[{"type":"ROLE","ids":["existing-role"]}]}',
  };
  const original = prepare(before, [
    { op: 'insert-node', as: 'a', route: 'route_1', node: { kind: 'approval', title: 'A' } },
  ]).design;
  const a = original.nodes.find((node) => !before.nodes.includes(node))!;
  original.nodes = original.nodes.map((node) => (node === a ? { ...task, nodeKey: a.nodeKey } : node));
  const result = prepare(original, [
    { op: 'update-node', node: 'approval', patch: { title: 'Edited title' } },
    { op: 'update-node', node: a.nodeKey, patch: { title: 'Task title' } },
  ]);
  expect(result.design.layoutJson).toBe(before.layoutJson);
  expect(result.design.nodes.find((node) => node.nodeKey === 'approval')).toEqual({
    ...before.nodes[1],
    title: 'Edited title',
  });
  expect(result.design.nodes.find((node) => node.nodeKey === a.nodeKey)).toEqual({
    ...task,
    nodeKey: a.nodeKey,
    title: 'Task title',
  });
});

describe('forbidden structures and unsupported participant inputs', () => {
  it.each([
    { id: 'forged' },
    { version: 99 },
    { nodeKey: 'replacement' },
    { nodeType: 'start' },
    { nodeStatus: 'published' },
    { participantPolicyText: '{"rules":[]}' },
    { nodeConfigText: '{}' },
    { convergeNodeKey: 'end' },
  ])('rejects raw node patch %o', (patch) => {
    expect(() =>
      prepare(createWorkflowDesign(false), [{ op: 'update-node', node: 'approval', patch }]),
    ).toThrow();
  });
  it.each([
    { type: 'USER', ids: ['made-up'] },
    { type: 'ROLE', ids: ['role'] },
    { type: 'ORG', ids: ['org'] },
    { type: 'FIELD', fieldName: 'unknown' },
    { type: 'FIELD', fieldName: 'amount' },
    { type: 'RELATIVE', relation: 'SUPERVISOR', depth: 0 },
    { type: 'RELATIVE', relation: 'SUPERVISOR', depth: 33 },
    { type: 'INITIATOR_SELF', ids: ['forged'] },
  ])('rejects unsupported or unverified rule %o', (rule) => {
    expect(() =>
      prepare(createWorkflowDesign(false), [
        { op: 'update-node', node: 'approval', patch: { participants: [rule] } },
      ]),
    ).toThrow();
  });
  it('rejects endpoint changes, invalid targets, incompatible properties and excessive capacities', () => {
    const design = createWorkflowDesign(false);
    for (const operation of [
      { op: 'update-route', route: 'route_1', patch: { targetNodeKey: 'end' } },
      { op: 'update-route', route: 'route_1', patch: { defaultRoute: true } },
      { op: 'update-node', node: 'start', patch: { title: 'Override start' } },
      { op: 'delete-node', node: 'end' },
      { op: 'update-node', node: 'approval', patch: { milestoneType: 'approval_completed' } },
      { op: 'update-node', node: 'approval', patch: { approvalRatio: 50 } },
      { op: 'update-node', node: 'approval', patch: { approvalRatio: 101 } },
      { op: 'update-node', node: 'approval\n', patch: { title: 'x' } },
    ])
      expect(() => prepare(design, [operation])).toThrow();
    const large = {
      ...design,
      nodes: Array.from({ length: 301 }, (_, i) => ({ nodeKey: `n${i}`, nodeType: 'approval' })),
    };
    expect(() => prepare(large, [{ op: 'update-node', node: 'n0', patch: { title: 'x' } }])).toThrow('容量');
    const atLimit = {
      ...design,
      nodes: [
        ...design.nodes,
        ...Array.from({ length: 297 }, (_, i) => ({ nodeKey: `n${i}`, nodeType: 'approval' })),
      ],
    };
    expect(() =>
      prepare(atLimit, [
        { op: 'insert-node', as: 'extra', route: 'route_1', node: { kind: 'approval', title: 'x' } },
      ]),
    ).toThrow('容量');
  });
});

it('deletes an ordinary inserted node while preserving incoming meaning', () => {
  const before = createWorkflowDesign(false);
  const result = prepare(before, [
    { op: 'insert-node', as: 'a', route: 'route_1', node: { kind: 'approval', title: 'A' } },
    { op: 'delete-node', node: ref('a', 'node') },
  ]);
  expect(result.design).toEqual(before);
  expect(result.changes).toEqual([]);
});

it('diagnoses only locally provable issues and explicitly separates formal validation', () => {
  const design = createWorkflowDesign(false);
  expect(diagnoseWorkflowDraft(design, fields)).toMatchObject({
    valid: false,
    publishedValidation: false,
    formulaCheck: 'requires-server-validation',
  });
  const valid = prepare(design, [{ op: 'update-node', node: 'approval', patch: { participants: self } }]);
  expect(valid.diagnostics.valid).toBe(true);
  const broken: WorkflowDesign = {
    nodes: [
      ...valid.design.nodes,
      { nodeKey: 'orphan', nodeType: 'branch', title: 'Bad branch', routeMode: 'manual' },
      { nodeKey: 'join', nodeType: 'converge', title: 'Join', convergeMode: 'ratio', convergeRatio: 200 },
    ],
    links: [
      ...valid.design.links,
      { routeKey: 'cycle', sourceNodeKey: 'approval', targetNodeKey: 'approval' },
      { routeKey: 'dangling', sourceNodeKey: 'start', targetNodeKey: 'missing' },
    ],
  };
  const diagnosis = diagnoseWorkflowDraft(broken, fields);
  expect(diagnosis.errors.join('；')).toMatch(/配对汇聚/);
  expect(diagnosis.errors.join('；')).toMatch(/不可达/);
  expect(diagnosis.errors.join('；')).toMatch(/循环/);
  expect(diagnosis.errors.join('；')).toMatch(/端点不存在/);
  const huge = {
    nodes: Array.from({ length: 100 }, (_, i) => ({ nodeKey: `n${i}`, nodeType: 'approval' })),
    links: [],
  };
  const bounded = diagnoseWorkflowDraft(huge);
  expect(bounded.errors).toHaveLength(40);
  expect(bounded.truncated).toBe(true);
});

it('reports dead ends, illegal simple-node exits and malformed or unchecked legacy participant facts', () => {
  const before = createWorkflowDesign(false);
  before.nodes[1]!.participantPolicyText = JSON.stringify({
    rules: [{ type: 'RELATIVE', relation: 'IMAGINARY', depth: 1 }],
  });
  const invalid = diagnoseWorkflowDraft(before, fields);
  expect(invalid.valid).toBe(false);
  expect(invalid.errors.join('；')).toContain('相对人员关系无效');
  before.nodes[1]!.participantPolicyText = JSON.stringify({
    rules: [{ type: 'RELATIVE', relation: 'SUPERVISOR', depth: 1, headOnly: true }],
  });
  const unchecked = diagnoseWorkflowDraft(before, fields);
  expect(unchecked.valid).toBe(false);
  expect(unchecked.warnings.join('；')).toContain('负责人筛选');
  before.nodes[1]!.participantPolicyText = JSON.stringify({
    rules: [{ type: 'INITIATOR_SELF', futureFlag: true }],
  });
  expect(diagnoseWorkflowDraft(before, fields)).toMatchObject({ valid: false });
  before.nodes[1]!.participantPolicyText = JSON.stringify({
    rules: [{ type: 'INITIATOR_SELF', headOnly: 'unknown' }],
  });
  expect(diagnoseWorkflowDraft(before, fields).warnings.join('；')).toContain('无法检查');
  before.links = [before.links[0]!];
  expect(diagnoseWorkflowDraft(before, fields).errors.join('；')).toContain('路径中断');
  before.links.push({ routeKey: 'extra', sourceNodeKey: 'start', targetNodeKey: 'end' });
  expect(diagnoseWorkflowDraft(before, fields).errors.join('；')).toContain('多个出口');
});

it('presents actual changes with domain labels and marks oversized diagnostics explicitly', () => {
  const before = createWorkflowDesign(false);
  const result = prepare(before, [
    {
      op: 'update-node',
      node: 'approval',
      patch: { title: 'Edited', approvalMode: 'any', participants: self },
    },
  ]);
  expect(result.changes.join('；')).toContain('参与人规则');
  expect(result.changes.join('；')).not.toMatch(/participantPolicyText|approvalMode/);
  const huge = {
    nodes: Array.from({ length: 301 }, (_, i) => ({ nodeKey: `n${i}`, nodeType: 'approval' })),
    links: [],
  };
  expect(diagnoseWorkflowDraft(huge)).toMatchObject({ valid: false, truncated: true });
  before.nodes[1]!.title = 'x'.repeat(1000);
  const bounded = diagnoseWorkflowDraft(before);
  expect(bounded.truncated).toBe(true);
  expect(bounded.errors.every((message) => message.length <= 500)).toBe(true);
});
