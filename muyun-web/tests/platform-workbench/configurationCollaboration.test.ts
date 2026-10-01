import { expect, it, vi } from 'vitest';
import { createConfigurationCollaboration } from '@/platform-workbench/configurationCollaboration';
import { createAssistantSurfaceRegistry, type AssistantCapability } from '@/web-core';

function fixture() {
  const collaboration = createConfigurationCollaboration();
  const saved = vi.fn(async () => ({ title: '已保存', lines: [] }));
  const capabilities: AssistantCapability[] = [
    {
      descriptor: { code: 'rules.select-module', description: '', inputSchema: {} },
      effect: 'configuration-draft',
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
  expect(f.codes()).toEqual(['rules.select-module', 'rules.describe']);
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
  expect(f.codes()).toEqual(['rules.select-module', 'rules.describe']);
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

it('uses shared page candidates by default and confines legacy page publication to explicit conversation mode', async () => {
  const collaboration = createConfigurationCollaboration();
  const publish = vi.fn(async () => ({ title: 'published', lines: [] }));
  const page: AssistantCapability = {
    descriptor: { code: 'construction.prepare-page', description: '', inputSchema: {} },
    effect: 'read',
    parseInput: (v) => v,
    execute: vi.fn(),
    propose: () => ({
      modelSummary: '',
      presentation: { title: '', lines: [] },
      confirmLabel: '',
      expiresAt: Date.now() + 60_000,
      lookup: async () => undefined,
      isCurrent: () => true,
      execute: publish,
    }),
  };
  expect(collaboration.filterConstruction([page])).toEqual([]);
  collaboration.restore({ goal: '页面配置', mode: 'visual' });
  expect(collaboration.filterConstruction([page])).toEqual([]);
  collaboration.restore({ goal: '页面配置', mode: 'conversation' });
  const proposal = collaboration.filterConstruction([page])[0]!.propose!({});
  collaboration.restore({ goal: '页面配置', mode: 'visual' });
  expect(proposal.isCurrent()).toBe(false);
  await expect(proposal.execute()).rejects.toThrow();
  expect(publish).not.toHaveBeenCalled();
});
