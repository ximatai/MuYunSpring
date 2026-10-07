import { expect, it, vi } from 'vitest';
import { createConfigurationCollaboration } from '@/platform-workbench/configurationCollaboration';
import { createAssistantSurfaceRegistry, type AssistantCapability } from '@/web-core';

function fixture() {
  const collaboration = createConfigurationCollaboration();
  const saved = vi.fn(async () => ({ title: '已保存', lines: [] }));
  const capabilities: AssistantCapability[] = [
    {
      descriptor: { code: 'rules.select-module', description: '', inputSchema: {} },
      effect: 'read',
      changesReadState: true,
      parseInput: (v) => v,
      execute: vi.fn(),
    },
    {
      descriptor: { code: 'rules.revise', description: '', inputSchema: {} },
      effect: 'configuration-draft',
      parseInput: (v) => v,
      execute: vi.fn(),
    },
    {
      descriptor: { code: 'rules.open-editor', description: '', inputSchema: {} },
      effect: 'page',
      parseInput: (v) => v,
      execute: vi.fn(),
    },
    {
      descriptor: { code: 'rules.describe', description: '', inputSchema: {} },
      effect: 'read',
      parseInput: (v) => v,
      execute: vi.fn(),
    },
    {
      descriptor: { code: 'rules.prepare-apply', description: '', inputSchema: {} },
      effect: 'read',
      parseInput: (v) => v,
      execute: vi.fn(async () => ({})),
      propose: () => ({
        presentation: { title: '保存', lines: [] },
        modelSummary: '待确认',
        confirmLabel: '确认',
        expiresAt: Date.now() + 60_000,
        lookup: async () => undefined,
        isCurrent: () => true,
        execute: saved,
      }),
    },
  ];
  const registry = createAssistantSurfaceRegistry(
    () => 'identity',
    () => ({ revision: String(collaboration.revision.value), facts: {} }),
  );
  registry.register({
    pageInstanceKey: 'page',
    contextRevision: () => '',
    surface: {
      describe: () => ({ surface: 'workbench', facts: {} }),
      capabilities: () => [...collaboration.capabilities(), ...collaboration.filter(capabilities)],
      requestTurn: vi.fn(),
    },
  });
  registry.activate('page');
  const invoke = (code: string, input: unknown = {}) =>
    registry.invoke({ id: code, code, input }, registry.snapshot()!.token);
  const codes = (visible = false) =>
    collaboration.filter(capabilities, visible).map((c) => c.descriptor.code);
  return { collaboration, saved, invoke, codes };
}

it('asks for a task mode before edits and holds that choice until an explicit switch', async () => {
  const f = fixture();
  expect(f.codes()).toEqual(['rules.select-module', 'rules.open-editor', 'rules.describe']);
  await expect(f.invoke('rules.revise')).rejects.toThrow();
  await f.invoke('configuration.start-task', { goal: '调整订单', mode: 'conversation' });
  expect(f.codes()).toContain('rules.prepare-apply');
  expect(f.codes()).not.toContain('rules.open-editor');
  await expect(f.invoke('rules.open-editor')).rejects.toThrow();
  const confirmation = (await f.invoke('rules.prepare-apply')).confirmation!;
  await f.invoke('configuration.switch-mode', { mode: 'visual' });
  expect(f.collaboration.task.value).toEqual({ goal: '调整订单', mode: 'visual' });
  expect(f.codes()).toContain('rules.open-editor');
  expect(f.codes()).not.toContain('rules.revise');
  expect(f.codes(true)).toContain('rules.revise');
  expect(f.codes()).toContain('rules.prepare-apply');
  await f.invoke('configuration.switch-mode', { mode: 'conversation' });
  await confirmation.confirm();
  expect(f.saved).not.toHaveBeenCalled();
  await f.invoke('configuration.finish-task');
  expect(f.collaboration.task.value).toBeUndefined();
  expect(f.codes()).not.toContain('rules.revise');
});

it('restores only the task preference and rejects malformed or implicit choices', async () => {
  const f = fixture();
  for (const input of [
    { goal: 'test', mode: ['visual'] },
    { goal: '', mode: 'visual' },
    { goal: 'x', mode: 'auto' },
  ])
    await expect(f.invoke('configuration.start-task', input)).rejects.toThrow();
  const snapshot = { goal: '调整字段', mode: 'visual' as const };
  f.collaboration.restore(snapshot);
  snapshot.goal = 'changed outside';
  expect(f.collaboration.task.value?.goal).toBe('调整字段');
  expect(f.codes()).toContain('rules.prepare-apply');
  expect(f.saved).not.toHaveBeenCalled();
  f.collaboration.restore();
  expect(f.codes()).toEqual(['rules.select-module', 'rules.open-editor', 'rules.describe']);
});

it('defaults to visual collaboration and supports conversation confirmation with the same proposal', async () => {
  const f = fixture();
  await f.invoke('configuration.start-task', { goal: '整理登记内容' });
  expect(f.collaboration.task.value?.mode).toBe('visual');
  const confirmation = (await f.invoke('rules.prepare-apply')).confirmation!;
  expect(f.saved).not.toHaveBeenCalled();
  await confirmation.confirm();
  expect(f.saved).toHaveBeenCalledOnce();
});

it('permits page discovery and navigation without a construction task and shares candidates across modes', () => {
  const collaboration = createConfigurationCollaboration();
  const capability = (code: string, effect: AssistantCapability['effect']): AssistantCapability => ({
    descriptor: { code, description: '', inputSchema: {} },
    effect,
    parseInput: (v) => v,
    execute: vi.fn(),
  });
  const select = capability('configuration.select-page-module', 'configuration-draft');
  const open = capability('configuration.open-page-editor', 'page');
  const revise = capability('configuration.revise-page-candidate', 'configuration-draft');
  expect(collaboration.filter([select, open, revise])).toEqual([select, open]);
  collaboration.restore({ goal: '页面配置', mode: 'conversation' });
  expect(collaboration.filter([select, open, revise])).toEqual([select, revise]);
  collaboration.restore({ goal: '页面配置', mode: 'visual' });
  expect(collaboration.filter([select, open, revise], true)).toEqual([select, open, revise]);
});

it('projects the visual opening boundary without losing the selected catalog or granting draft authority', () => {
  const { collaboration, codes } = fixture();
  expect(collaboration.boundary()).toMatchObject({
    state: 'START_TASK_REQUIRED',
    draftEditingAvailable: false,
  });
  expect(collaboration.boundary().guidance).toContain('authorized governance reads remain available');
  collaboration.restore({ goal: '配置业务规则', mode: 'visual' });
  expect(collaboration.boundary()).toMatchObject({
    appliesTo: 'selected-module-metadata-page-and-rule-candidates',
    state: 'SELECT_CONFIGURATION_TARGET_REQUIRED',
    draftEditingAvailable: false,
  });
  expect(collaboration.boundary().guidance).toContain('does not block requirements planning');
  expect(collaboration.boundary().guidance).toContain('target selection is not a new approval');
  expect(codes()).toContain('rules.select-module');
  expect(codes()).toContain('rules.open-editor');
  expect(codes()).not.toContain('rules.revise');
  expect(collaboration.boundary({ visible: false })).toMatchObject({
    state: 'OPEN_SELECTED_EDITOR_REQUIRED',
    draftEditingAvailable: false,
  });
  const hidden = codes();
  expect(hidden).toContain('rules.describe');
  expect(hidden).toContain('rules.open-editor');
  expect(hidden).not.toContain('rules.revise');
  expect(collaboration.boundary({ visible: true })).toMatchObject({
    state: 'READY',
    draftEditingAvailable: true,
  });
  expect(codes(true)).toContain('rules.revise');
  collaboration.restore({ goal: '配置业务规则', mode: 'conversation' });
  expect(collaboration.boundary({ visible: false })).toMatchObject({
    state: 'READY',
    draftEditingAvailable: true,
  });
});

it('continues confirmed configuration without a plan binding and stops continuation after task changes', async () => {
  const f = fixture();
  await f.invoke('configuration.start-task', { goal: '完成全部模块' });
  const confirmation = (await f.invoke('rules.prepare-apply')).confirmation!;
  expect(confirmation.takeContinuation()).toBeUndefined();
  await confirmation.confirm();
  expect(confirmation.state).toBe('succeeded');
  expect(confirmation.takeContinuation()).toContain('新的保存仍须独立确认');
  expect(confirmation.takeContinuation()).toBeUndefined();
  const second = (await f.invoke('rules.prepare-apply')).confirmation!;
  await second.confirm();
  await f.invoke('configuration.finish-task');
  expect(second.takeContinuation()).toBeUndefined();
});

it('retains an explicit read-only continuation rather than widening an adapter policy', () => {
  const f = fixture();
  f.collaboration.restore({ goal: '只比较', mode: 'visual' });
  const original = { message: '只核实', readOnly: true, isCurrent: () => true };
  const capability: AssistantCapability = {
    descriptor: { code: 'configuration.confirm', description: '', inputSchema: {} },
    effect: 'read',
    parseInput: (value) => value,
    execute: vi.fn(),
    propose: () => ({
      presentation: { title: '确认', lines: [] },
      confirmLabel: '确认',
      expiresAt: Date.now() + 1000,
      isCurrent: () => true,
      execute: vi.fn(),
      continuation: original,
      lookup: async () => undefined,
    }),
  };
  expect(f.collaboration.filter([capability])[0]!.propose!({}).continuation).toBe(original);
  expect(
    f.collaboration.boundary({
      visible: false,
      kind: 'metadata',
      openingCapability: 'configuration.open-metadata-editor',
    }),
  ).toMatchObject({
    editorKind: 'metadata',
    openingCapability: 'configuration.open-metadata-editor',
    draftEditingAvailable: false,
  });
});
