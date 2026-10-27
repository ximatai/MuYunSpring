import { describe, expect, it, vi } from 'vitest';
import { createAssistantSurfaceRegistry, createLanguageContext } from '@muyun/web-core';
import {
  createWorkflowConfigurationAssistantSurface,
  type WorkflowConfigurationAssistantState,
} from '@/views/workflowConfigurationAssistantSurface';
import { insertWorkflowBranch, appendWorkflowBranchPath } from '@/platform-components/workflowDesignEditing';
import { prepareWorkflowDesignOperations } from '@/platform-components/workflowDesignOperations';

function fixture(tag: string) {
  const language = createLanguageContext({ languageTag: tag });
  const state: WorkflowConfigurationAssistantState = {
    ready: true,
    editable: true,
    moduleAlias: 'demo.purchase',
    version: { id: 'v1', version: 2, versionNo: 1, publishStatus: 'draft' },
    design: {
      nodes: [
        { nodeKey: 'start', nodeType: 'start', title: '提交原文' },
        { nodeKey: 'approval', nodeType: 'approval', title: '审批原文' },
        { nodeKey: 'end', nodeType: 'end', title: '完成原文' },
      ],
      links: [
        { routeKey: 'r1', sourceNodeKey: 'start', targetNodeKey: 'approval' },
        { routeKey: 'r2', sourceNodeKey: 'approval', targetNodeKey: 'end' },
      ],
    },
    fields: [{ name: 'owner', label: '负责人原文', referenceModule: 'iam.user' }],
    selectedNodeKeys: [],
    selectedRouteKey: '',
    dirty: false,
  };
  const surface = createWorkflowConfigurationAssistantSurface(
    {
      language,
      state: () => state,
      validateCondition: vi.fn(async () => {
        throw new Error('server detail');
      }),
      prepare: (batch) => {
        const result = prepareWorkflowDesignOperations(state.design, batch, state.fields, language);
        return () => {
          state.design = result.design;
          state.dirty = true;
          return result;
        };
      },
    },
    vi.fn(async () => ({ toolCalls: [] })),
  );
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'workflow',
    contextRevision: () => JSON.stringify(state.design),
    surface,
  });
  registry.activate('workflow');
  const invoke = (code: string, input: unknown = {}) =>
    registry.invoke({ id: 'call', code, input }, registry.snapshot()!.token);
  return { language, state, surface, invoke };
}

describe('workflow assistant language contract', () => {
  it.each([
    [
      'zh-CN',
      '流程草稿已调整（未保存）',
      '修改节点',
      '人工保存',
      '参与人未配置或无效',
      '条件公式检查未通过',
      '流程操作参数无效',
      '节点不存在',
    ],
    [
      'en',
      'Workflow draft updated (unsaved)',
      'Updated Node',
      'manual save',
      'Participants are missing or invalid',
      'The condition check failed',
      'Invalid workflow operations',
      'The node does not exist',
    ],
  ])(
    'localizes real results, diagnostics and rejected input in %s while preserving user text',
    async (tag, title, change, handoff, participants, formula, invalid, missing) => {
      const { invoke, state } = fixture(tag);
      const updated = await invoke('workflow.revise-draft', {
        operations: [{ op: 'update-node', node: 'approval', patch: { title: '新审批原文' } }],
      });
      expect(updated.presentation?.title).toBe(title);
      expect(updated.presentation?.lines?.join(' ')).toContain(change);
      expect(updated.presentation?.lines?.join(' ')).toContain(handoff);
      expect(updated.presentation?.lines?.join(' ')).toContain('新审批原文');
      expect(updated.value).toMatchObject({ unsaved: true, saved: false, published: false });
      const description = await invoke('workflow.describe-draft');
      expect(JSON.stringify(description.value)).toContain('负责人原文');
      expect(JSON.stringify(description.value)).toContain('新审批原文');
      state.design.links[0]!.conditionExpression = 'owner != null';
      const diagnostics = await invoke('workflow.diagnose-draft');
      expect(diagnostics.presentation?.lines?.join(' ')).toContain(participants);
      expect(diagnostics.presentation?.lines?.join(' ')).toContain(formula);
      await expect(invoke('workflow.revise-draft', { invalid: true })).rejects.toThrow(invalid);
      await expect(
        invoke('workflow.revise-draft', {
          operations: [{ op: 'delete-node', node: 'missing' }],
        }),
      ).rejects.toThrow(missing);
    },
  );

  it('uses the current LanguageContext after a language change and localizes only generated defaults', async () => {
    const { language, state, invoke } = fixture('zh-CN');
    language.setLanguage('en');
    const result = await invoke('workflow.revise-draft', {
      operations: [{ op: 'insert-branch', as: 'b', route: 'r2', title: '人工分支原文' }],
    });
    expect(result.presentation?.title).toBe('Workflow draft updated (unsaved)');
    expect(result.presentation?.lines?.join(' ')).toContain('Branch approval 1');
    expect(state.design.nodes.some((node) => node.title === '人工分支原文')).toBe(true);
    expect(state.design.nodes.some((node) => node.title === 'Branch join')).toBe(true);
    expect(state.design.nodes.find((node) => node.nodeKey === 'approval')?.title).toBe('审批原文');
    expect(state.design.links.some((route) => route.title === 'Path 1')).toBe(true);
    await expect(
      invoke('workflow.revise-draft', {
        operations: [
          { op: 'insert-node', as: 'new', route: 'missing', node: { kind: 'approval', title: '新节点' } },
        ],
      }),
    ).rejects.toThrow('Choose a route for the new node');
  });
});

it('shares generated branch titles with manual editing and preserves default-like user text', async () => {
  const { language, state, invoke } = fixture('en');
  state.design.nodes[1]!.title = '分支审批1';
  state.design.links[1]!.title = '继续';
  const manual = appendWorkflowBranchPath(
    insertWorkflowBranch(state.design, 'r2', 'manual', language),
    'manual',
    'third',
    language,
  );
  await invoke('workflow.revise-draft', {
    operations: [
      { op: 'insert-branch', as: 'b', route: 'r2', title: '条件分支' },
      { op: 'append-path', as: 'p', branch: { created: 'b', part: 'branch' } },
      { op: 'insert-node', as: 'n', route: 'r1', node: { kind: 'approval', title: '分支审批1' } },
    ],
  });
  expect(state.design.nodes.slice(4, 8).map((node) => node.title)).toEqual(
    manual.nodes.slice(4).map((node) => node.title),
  );
  expect(state.design.nodes[3]!.title).toBe('条件分支');
  expect(state.design.nodes[1]!.title).toBe('分支审批1');
  expect(state.design.nodes.at(-1)?.title).toBe('分支审批1');
  expect(state.design.links[1]!.title).toBe('继续');
  expect(state.design.links.slice(2, 9).map((route) => route.title)).toEqual(
    manual.links.slice(2).map((route) => route.title),
  );
});
