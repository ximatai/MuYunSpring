import { describe, expect, it, vi } from 'vitest';
import { AssistantCapabilityUsageError, createAssistantSurfaceRegistry } from '@muyun/web-core';
import {
  createWorkflowConfigurationAssistantSurface,
  describeWorkflowDraft,
  type WorkflowConfigurationAssistantState,
} from '@/views/workflowConfigurationAssistantSurface';
import { createWorkflowDesign, insertWorkflowBranch } from '@/platform-components/workflowDesignEditing';
import {
  prepareWorkflowDesignOperations,
  workflowDesignOperationsSchema,
} from '@/platform-components/workflowDesignOperations';

function fixture(validateCondition?: (expression: string, signal: AbortSignal) => Promise<void>) {
  const state: WorkflowConfigurationAssistantState = {
    ready: true,
    editable: true,
    moduleAlias: 'demo.purchase',
    definition: {
      id: 'definition',
      version: 3,
      title: '采购审批',
      definitionStatus: 'draft',
      approvalEnabled: false,
    },
    version: { id: 'v1', version: 2, versionNo: 1, publishStatus: 'draft' },
    design: createWorkflowDesign(false),
    fields: [{ name: 'owner', label: '负责人', referenceModule: 'iam.user' }],
    selectedNodeKeys: ['approval'],
    selectedRouteKey: '',
    dirty: false,
  };
  const requestTurn = vi.fn(async () => ({ toolCalls: [] }));
  const commit = vi.fn();
  let revision = 0;
  const surface = createWorkflowConfigurationAssistantSurface(
    {
      state: () => state,
      validateCondition,
      prepare(batch) {
        const expected = revision;
        const result = prepareWorkflowDesignOperations(state.design, batch, state.fields);
        return () => {
          if (expected !== revision || !state.editable || !state.ready) throw new Error('stale');
          state.design = result.design;
          state.dirty = true;
          revision++;
          commit();
          return result;
        };
      },
    },
    requestTurn,
  );
  const registry = createAssistantSurfaceRegistry();
  registry.register({ pageInstanceKey: 'workflow', contextRevision: () => String(revision), surface });
  registry.activate('workflow');
  const invoke = (code: string, input: unknown = {}, readOnly = false) =>
    registry.invoke({ id: 'call', code, input }, registry.snapshot()!.token, undefined, { readOnly });
  return { state, surface, registry, invoke, requestTurn, commit };
}

describe('workflow configuration surface', () => {
  it('checks conditions only through the read-only endpoint with deduplication and explicit limits', async () => {
    const validate = vi.fn(async (expression: string) => {
      if (expression === 'invalid') throw new Error('server rejected');
    });
    const { state, invoke, commit } = fixture(validate);
    state.design.links = Array.from({ length: 20 }, (_, index) => ({
      routeKey: `r${index}`,
      sourceNodeKey: 'start',
      targetNodeKey: 'end',
      conditionExpression: index < 2 ? 'invalid' : `value_${index}`,
    }));
    const result = await invoke('workflow.diagnose-draft');
    expect(validate).toHaveBeenCalledTimes(16);
    expect(result.value).toMatchObject({
      valid: false,
      truncated: true,
      publishedValidation: false,
      formulaChecks: {
        total: 19,
        checked: 16,
        omitted: 3,
        officialPublicationValidation: false,
        entries: expect.arrayContaining([expect.objectContaining({ expression: 'invalid', valid: false })]),
      },
    });
    expect(commit).not.toHaveBeenCalled();
    expect(result.presentation?.lines?.join(' ')).toContain('条件公式检查未通过');
  });

  it('discards late formula checks after a revision change and never commits read effects', async () => {
    let finish!: () => void;
    const validate = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    const { state, invoke, commit } = fixture(validate);
    state.design.links[0]!.conditionExpression = 'pending';
    const pending = invoke('workflow.diagnose-draft');
    const rejected = expect(pending).rejects.toThrow();
    await invoke('workflow.revise-draft', {
      operations: [{ op: 'update-node', node: 'approval', patch: { title: 'new revision' } }],
    });
    finish();
    await rejected;
    expect(commit).toHaveBeenCalledOnce();
    expect(validate).toHaveBeenCalledOnce();
  });

  it('shares operation schema and parser, refuses undeclared inputs before producing any effect', async () => {
    const { surface, state, invoke, commit } = fixture();
    const capability = surface
      .capabilities()
      .find((item) => item.descriptor.code === 'workflow.revise-draft')!;
    expect(capability.descriptor.inputSchema).toEqual(workflowDesignOperationsSchema());
    const before = JSON.stringify(state.design);
    for (const input of [
      { design: state.design },
      { operations: [{ op: 'update-node', node: 'approval', patch: { title: '新名称', id: 'fake' } }] },
      { operations: [{ op: 'update-route', route: 'route_1', patch: { targetNodeKey: 'end' } }] },
      {
        operations: [
          { op: 'update-node', node: 'approval', patch: { participantPolicyText: 'user:guessed' } },
        ],
      },
      {
        operations: [
          {
            op: 'update-node',
            node: 'approval',
            patch: { participants: [{ type: 'USER', ids: ['guessed'] }] },
          },
        ],
      },
    ]) {
      expect(() => capability.parseInput(input)).toThrow(AssistantCapabilityUsageError);
      await expect(invoke('workflow.revise-draft', input)).rejects.toThrow();
    }
    expect(JSON.stringify(state.design)).toBe(before);
    expect(commit).not.toHaveBeenCalled();
  });

  it('rejects assistant conditions outside the visible directory while preserving manual configuration', async () => {
    const { state, invoke, commit } = fixture();
    state.fields = [...state.fields, { name: 'hiddenAmount', assistantPolicy: 'HIDDEN' }];
    state.design = insertWorkflowBranch(state.design, 'route_1', 'branch');
    state.design.links.find((route) => route.routeKey === 'branch_exit1')!.conditionExpression =
      '{hiddenAmount} > 100';
    const before = JSON.stringify(state.design);
    for (const expression of ['{hiddenAmount} > 10', '{absentAmount} > 10', '{owner.privateValue} != null']) {
      await expect(
        invoke('workflow.revise-draft', {
          operations: [
            { op: 'update-node', node: 'approval', patch: { title: 'must not be partially applied' } },
            { op: 'update-route', route: 'branch_exit1', patch: { conditionExpression: expression } },
          ],
        }),
      ).rejects.toThrow(AssistantCapabilityUsageError);
      expect(JSON.stringify(state.design)).toBe(before);
    }
    expect(commit).not.toHaveBeenCalled();
    await invoke('workflow.revise-draft', {
      operations: [{ op: 'update-node', node: 'approval', patch: { title: 'unrelated edit' } }],
    });
    expect(state.design.links.find((route) => route.routeKey === 'branch_exit1')!.conditionExpression).toBe(
      '{hiddenAmount} > 100',
    );
    await invoke('workflow.revise-draft', {
      operations: [
        { op: 'update-route', route: 'branch_exit1', patch: { conditionExpression: '{owner} != null' } },
      ],
    });
    expect(state.design.links.find((route) => route.routeKey === 'branch_exit1')!.conditionExpression).toBe(
      '{owner} != null',
    );
  });

  it('reports actual changes as unsaved and uses a single guarded page effect without persistence', async () => {
    const { state, invoke, commit, requestTurn } = fixture();
    const result = await invoke('workflow.revise-draft', {
      operations: [
        {
          op: 'update-node',
          node: 'approval',
          patch: { title: '负责人审批', participants: [{ type: 'FIELD', fieldName: 'owner' }] },
        },
        {
          op: 'insert-node',
          as: 'notice',
          route: 'route_2',
          node: { kind: 'notice', title: '通知提交人', participants: [{ type: 'INITIATOR_SELF' }] },
        },
      ],
    });
    expect(result.contextChanged).toBe(true);
    expect(commit).toHaveBeenCalledOnce();
    expect(requestTurn).not.toHaveBeenCalled();
    expect(state.design.nodes.find((node) => node.nodeKey === 'approval')?.title).toBe('负责人审批');
    expect(result.value).toMatchObject({ unsaved: true, saved: false, published: false });
    expect(result.value).not.toHaveProperty('design');
    expect(result.presentation?.lines?.join(' ')).toContain('未保存');
    expect(result.presentation?.lines?.join(' ')).toContain('负责人审批');
    expect(result.presentation?.lines?.join(' ')).toContain('人工保存');
  });

  it('rejects the complete batch before applyEffect when a later operation cannot apply', async () => {
    const { state, invoke, commit } = fixture();
    const before = JSON.stringify(state.design);
    await expect(
      invoke('workflow.revise-draft', {
        operations: [
          { op: 'update-node', node: 'approval', patch: { title: '不能部分应用' } },
          { op: 'delete-node', node: 'missing' },
        ],
      }),
    ).rejects.toThrow(AssistantCapabilityUsageError);
    expect(JSON.stringify(state.design)).toBe(before);
    expect(commit).not.toHaveBeenCalled();
  });

  it('filters writes for frozen, unauthorized, busy or unloaded drafts and enforces readOnly execution', async () => {
    const { state, surface, invoke } = fixture();
    await expect(
      invoke(
        'workflow.revise-draft',
        {
          operations: [{ op: 'update-node', node: 'approval', patch: { title: 'blocked' } }],
        },
        true,
      ),
    ).rejects.toThrow();
    state.busy = true;
    expect(surface.capabilities().some((item) => item.effect === 'configuration-draft')).toBe(false);
    state.busy = false;
    state.version!.publishStatus = 'published';
    expect(surface.capabilities().map((item) => item.descriptor.code)).toEqual([
      'workflow.describe-draft',
      'workflow.diagnose-draft',
    ]);
    const read = await invoke('workflow.describe-draft');
    expect(read.value).toMatchObject({ editable: false, version: { publishStatus: 'published' } });
    state.version!.publishStatus = 'draft';
    state.editable = false;
    expect(surface.capabilities().some((item) => item.effect === 'configuration-draft')).toBe(false);
    state.ready = false;
    expect(surface.capabilities()).toEqual([]);
    expect(surface.describe()).toMatchObject({ facts: { ready: false, editable: false } });
    expect(surface.describe().facts).not.toHaveProperty('unsaved');
  });

  it('diagnoses locally without saving, publishing or calling model transport', async () => {
    const { state, invoke, commit, requestTurn } = fixture();
    const before = JSON.stringify(state.design);
    const result = await invoke('workflow.diagnose-draft');
    expect(result.value).toMatchObject({
      valid: false,
      publishedValidation: false,
      formulaCheck: 'requires-server-validation',
      saved: false,
      published: false,
    });
    expect(JSON.stringify(state.design)).toBe(before);
    expect(commit).not.toHaveBeenCalled();
    expect(requestTurn).not.toHaveBeenCalled();
  });

  it('projects selected graph facts and preserves the existence of manual participant and business-task configuration', () => {
    const { state } = fixture();
    state.fields = [...state.fields, { name: 'done', label: '已完成' }];
    state.design.nodes[1]!.participantPolicyText = '{"rules":[{"type":"ROLE","ids":["private-role-id"]}]}';
    state.design.nodes.push({
      nodeKey: 'task',
      nodeType: 'task',
      title: '人工任务',
      taskDefinitionId: 'task-ref',
      nodeConfigText: JSON.stringify({
        definition: { manualConfirm: true },
        checks: [
          {
            checkKey: 'done',
            checkKind: 'FORMULA',
            enabled: true,
            expression: '{done} = true',
            checkConfigText: 'private config',
          },
        ],
        guides: [
          {
            guideKey: 'guide',
            guideKind: 'ACTION',
            title: '办理操作',
            enabled: true,
            guideConfigText: 'private config',
          },
        ],
      }),
    });
    const result = describeWorkflowDraft(state);
    expect(result.selectedNodeKeys).toEqual(['approval']);
    expect(result.fields.entries[0]).toMatchObject({ name: 'owner', participantSupported: true });
    expect(result.nodes.entries[1]?.participants).toMatchObject({
      rules: [{ type: 'ROLE', targetCount: 1, manualConfigurationRequired: true }],
    });
    expect(result.nodes.entries.find((node) => node.nodeKey === 'task')?.businessTask).toMatchObject({
      configured: true,
      manualConfigurationRequired: true,
      checks: {
        total: 1,
        entries: [{ kind: 'FORMULA', expression: '{done} = true', advancedConfigurationPreserved: true }],
      },
      guides: { total: 1 },
    });
    expect(JSON.stringify(result)).not.toContain('private-role-id');
    expect(JSON.stringify(result)).not.toContain('private config');
    expect(result.truncated).toBe(false);
  });

  it('omits retained references absent from the current field directory without changing the manual draft', async () => {
    const validate = vi.fn(async () => undefined);
    const { state, invoke } = fixture(validate);
    state.design.nodes[1]!.participantPolicyText = JSON.stringify({
      rules: [{ type: 'FIELD', fieldName: 'privateOwner' }],
    });
    state.design.nodes[1]!.nodeConfigText = JSON.stringify({
      checks: [{ checkKind: 'FORMULA', expression: '{privateAmount} > 100' }],
    });
    state.design.links[0]!.conditionExpression = '{privateAmount} > 100';
    const original = JSON.stringify(state.design);
    const description = describeWorkflowDraft(state);
    expect(description.nodes.entries[1]?.participants).toMatchObject({
      configured: true,
      rules: [{ manualConfigurationRequired: true }],
    });
    expect(JSON.stringify(description)).not.toContain('privateOwner');
    expect(JSON.stringify(description)).not.toContain('privateAmount');
    const result = await invoke('workflow.diagnose-draft');
    expect(validate).not.toHaveBeenCalled();
    expect(result.value).toMatchObject({ formulaChecks: { total: 1, checked: 0, omitted: 1 } });
    expect(JSON.stringify(result.value)).not.toContain('privateAmount');
    expect((result.value as { errors: string[] }).errors.join(' ')).not.toContain('参与人账号字段不可用');
    expect((result.value as { warnings: string[] }).warnings.join(' ')).toContain('本地无法检查');
    expect(JSON.stringify(state.design)).toBe(original);
  });

  it('marks every capacity omission and text clipping while bounding the complete read payload', () => {
    const { state } = fixture();
    state.definition!.title = 'x'.repeat(2000);
    Object.assign(state.version!, {
      semanticJson: 'private-snapshot'.repeat(10000),
      layoutJson: 'private-layout'.repeat(10000),
      frozen: true,
    });
    state.design.layoutJson = 'secret-layout';
    state.design.nodes = Array.from({ length: 200 }, (_, index) => ({
      nodeKey: `node_${index}`,
      nodeType: 'approval',
      title: 'x'.repeat(2000),
      participantPolicyText: '{"rules":[]}',
    }));
    state.design.links = Array.from({ length: 200 }, (_, index) => ({
      routeKey: `route_${index}`,
      sourceNodeKey: `node_${index}`,
      targetNodeKey: `node_${index + 1}`,
      conditionExpression: 'x'.repeat(2000),
    }));
    state.fields = Array.from({ length: 200 }, (_, index) => ({
      name: `field_${index}`,
      label: 'x'.repeat(2000),
    }));
    const result = describeWorkflowDraft(state);
    expect(result.truncated).toBe(true);
    expect(result.nodes.omitted).toBeGreaterThan(0);
    expect(result.routes.omitted).toBeGreaterThan(0);
    expect(result.fields.omitted).toBeGreaterThan(0);
    expect(JSON.stringify(result).length).toBeLessThan(12500);
    expect(JSON.stringify(result)).not.toContain('secret-layout');
    expect(JSON.stringify(result)).not.toContain('private-snapshot');
    expect(JSON.stringify(result)).not.toContain('private-layout');
    expect(result.version).not.toHaveProperty('frozen');
  });
});
