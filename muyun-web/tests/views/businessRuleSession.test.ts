import { afterEach, expect, it, vi } from 'vitest';
import { effectScope } from 'vue';
import { createBusinessRuleWorkspace } from '@/views/businessRuleWorkspace';
import { createAssistantSurfaceRegistry, type HttpClient } from '@/web-core';
import type { BusinessRuleProposal } from '@/views/businessRuleGovernance';

const workspaces: ReturnType<typeof createBusinessRuleWorkspace>[] = [];
afterEach(() => workspaces.splice(0).forEach((workspace) => workspace.dispose()));

function fixture() {
  let identity = 'user-a';
  const rule: BusinessRuleProposal = {
    code: 'positive',
    kind: 'VALIDATION',
    expression: '{amount} >= 0',
    enabled: true,
  };
  const snapshot = {
    moduleAlias: 'demo.order',
    baselineFingerprint: 'base',
    editableFields: [{ fieldName: 'amount', title: '金额', valueType: 'DECIMAL', fieldSpecAlias: 'decimal' }],
    rules: [],
  };
  const http: HttpClient = {
    request: vi.fn(async (options) => {
      if (options.path.endsWith('/ui-controls'))
        return { baselineFingerprint: 'ui', forms: [], rules: [] } as never;
      if (options.path.endsWith('/preview'))
        return {
          errors: [],
          executionOrder: ['positive'],
          proposalFingerprint: 'checked',
          snapshot,
        } as never;
      if (options.path.endsWith('/trial'))
        return { values: {}, changedFields: [], errors: [], preview: {} } as never;
      if (options.path.endsWith('/apply'))
        return { snapshot, preview: {}, activatedModules: ['demo.order'] } as never;
      return snapshot as never;
    }),
  };
  const openRules = vi.fn((alias: string) => workspace.showEditor(workspace.session(alias)));
  const settleNavigation = vi.fn(async () => {});
  const workspace = createBusinessRuleWorkspace(
    http,
    () => identity,
    () => true,
    openRules,
  );
  workspaces.push(workspace);
  const registry = createAssistantSurfaceRegistry(() => identity, workspace.current);
  registry.register({
    pageInstanceKey: 'page',
    contextRevision: () => '',
    surface: {
      describe: () => ({ surface: 'workbench', facts: {} }),
      capabilities: () => workspace.capabilities(settleNavigation),
      requestTurn: vi.fn(),
    },
  });
  registry.activate('page');
  const invoke = (code: string, input: unknown = {}) =>
    registry.invoke({ id: 'call', code, input }, registry.snapshot()!.token);
  return {
    http,
    workspace,
    openRules,
    settleNavigation,
    invoke,
    rule,
    identity: (value: string) => {
      identity = value;
      workspace.current();
    },
  };
}

it('prepares and trials without a mounted page, shares edits, and invalidates the old confirmation', async () => {
  const { workspace, invoke, rule, http } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  await invoke('rules.revise', rule);
  await invoke('rules.trial', { sampleValues: { amount: 18 } });
  const prepared = await invoke('rules.prepare-apply');
  const pageSession = workspace.session('demo.order');
  await pageSession.load();
  expect(pageSession.rules.value).toEqual([rule]);
  pageSession.replace([{ ...rule, expression: '{amount} > 0' }]);
  await prepared.confirmation!.confirm();
  expect(
    vi.mocked(http.request).mock.calls.filter(([request]) => request.path.endsWith('/apply')),
  ).toHaveLength(0);
  const fresh = await invoke('rules.prepare-apply');
  await fresh.confirmation!.confirm();
  expect(
    vi.mocked(http.request).mock.calls.filter(([request]) => request.path.endsWith('/apply')),
  ).toHaveLength(1);
});

it('does not revive an old editor or confirmation after leaving and returning to the same identity', async () => {
  const { workspace, invoke, rule, identity } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  await invoke('rules.revise', rule);
  const old = workspace.session('demo.order');
  const confirmation = await old.adapter.prepareConfirmation(new AbortController().signal);
  identity('user-b');
  identity('user-a');
  expect(confirmation.isCurrent()).toBe(false);
  expect(() => old.replace([])).toThrow('身份已变化');
  expect(workspace.session('demo.order')).not.toBe(old);
});

it('blocks assistant changes while a page editor is open and retains a candidate across navigation', async () => {
  const { workspace, invoke, rule } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  const pageSession = workspace.session('demo.order');
  const editor = Symbol('editor');
  pageSession.setEditing(editor, true);
  expect(() => pageSession.adapter.revise(rule)).toThrow('规则编辑');
  pageSession.setEditing(editor, false);
  await invoke('rules.revise', rule);
  await pageSession.load();
  expect(pageSession.dirty.value).toBe(true);
  expect(pageSession.rules.value).toEqual([rule]);
});

it('keeps an untouched module out of the active context and rejects confirmations after target switches', async () => {
  const { workspace, invoke, rule } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  await invoke('rules.revise', rule);
  const selected = workspace.session('demo.order');
  const confirmation = await selected.adapter.prepareConfirmation(new AbortController().signal);
  const revision = workspace.current().revision;
  const other = workspace.session('demo.other');
  expect(workspace.current().revision).toBe(revision);
  workspace.focus(other);
  expect(confirmation.isCurrent()).toBe(false);
  workspace.focus(selected);
  expect(confirmation.isCurrent()).toBe(false);
});

it('reloads clean baselines explicitly while refusing to overwrite unsaved candidates', async () => {
  const { workspace, http, rule } = fixture();
  const session = workspace.session('demo.order');
  await session.load();
  const reads = () =>
    vi.mocked(http.request).mock.calls.filter(([request]) => request.path.endsWith('/business-rules')).length;
  expect(reads()).toBe(1);
  await session.load(true);
  expect(reads()).toBe(2);
  session.adapter.revise(rule);
  await expect(session.load(true)).rejects.toThrow('未保存更改');
  expect(session.rules.value).toEqual([rule]);
});

it('opens only the selected candidate through guarded navigation without reloading or saving it', async () => {
  const { workspace, invoke, rule, http, openRules, settleNavigation } = fixture();
  expect(
    workspace
      .capabilities(settleNavigation)
      .some((capability) => capability.descriptor.code === 'rules.open-editor'),
  ).toBe(false);
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  await invoke('rules.revise', rule);
  const session = workspace.session('demo.order');
  const reads = vi.mocked(http.request).mock.calls.length;
  await invoke('rules.open-editor');
  expect(openRules).toHaveBeenCalledExactlyOnceWith('demo.order');
  expect(settleNavigation).toHaveBeenCalledOnce();
  expect(session.rules.value).toEqual([rule]);
  expect(session.dirty.value).toBe(true);
  expect(vi.mocked(http.request).mock.calls).toHaveLength(reads);
  const capability = workspace
    .capabilities(settleNavigation)
    .find((item) => item.descriptor.code === 'rules.open-editor')!;
  expect(() => capability.parseInput({ moduleAlias: 'demo.other' })).toThrow();
  expect(() => capability.parseInput({ url: '/arbitrary' })).toThrow();
});

it('keeps draft invalidation alive after the page that first created the session is destroyed', async () => {
  const { workspace, invoke, rule, http } = fixture();
  const pageScope = effectScope();
  const session = pageScope.run(() => workspace.session('demo.order'))!;
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  await invoke('rules.revise', rule);
  pageScope.stop();
  const prepared = await invoke('rules.prepare-apply');
  session.replace([{ ...rule, expression: '{amount} > 100' }]);
  await prepared.confirmation!.confirm();
  expect(
    vi.mocked(http.request).mock.calls.filter(([request]) => request.path.endsWith('/apply')),
  ).toHaveLength(0);
  const fresh = await invoke('rules.prepare-apply');
  await fresh.confirmation!.confirm();
  expect(
    vi.mocked(http.request).mock.calls.filter(([request]) => request.path.endsWith('/apply')),
  ).toHaveLength(1);
});

it('retries a failed refresh through module selection despite retaining an older baseline', async () => {
  const { workspace, invoke, rule, http } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  const session = workspace.session('demo.order');
  vi.mocked(http.request).mockRejectedValueOnce(new Error('network unavailable'));
  await expect(session.load(true)).rejects.toThrow('network unavailable');
  expect(session.adapter.summary().editable).toBe(false);
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  expect(session.adapter.summary().editable).toBe(true);
  await invoke('rules.revise', rule);
  expect(session.rules.value).toEqual([rule]);
});

it('revokes pending confirmation and mutations when the workspace is disposed', async () => {
  const { workspace, invoke, rule } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  await invoke('rules.revise', rule);
  const session = workspace.session('demo.order');
  const confirmation = await session.adapter.prepareConfirmation(new AbortController().signal);
  workspace.dispose();
  expect(confirmation.isCurrent()).toBe(false);
  expect(() => session.replace([])).toThrow();
});
