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
    childFields: [
      { fieldName: 'lines.amount', title: '明细小计', valueType: 'DECIMAL', fieldSpecAlias: 'decimal' },
    ],
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
    registry,
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

it('shares direct-child calculation targets between the assistant and governance session', async () => {
  const { workspace, invoke } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  const rule: BusinessRuleProposal = {
    code: 'lineAmount',
    kind: 'CALCULATION',
    targetField: 'lines.amount',
    expression: '{lines.quantity} * {lines.price}',
    enabled: true,
  };
  await invoke('rules.revise', rule);
  expect(workspace.session('demo.order').rules.value).toEqual([rule]);
  const prepared = await invoke('rules.prepare-apply');
  expect(prepared.confirmation!.presentation.lines.join('；')).toContain('明细小计');
  await expect(invoke('rules.revise', { ...rule, targetField: 'supplier.amount' })).rejects.toThrow();
  expect(workspace.session('demo.order').rules.value).toEqual([rule]);
});

it('allows selecting authorized rule facts during read-only continuation without exposing mutations', async () => {
  const { workspace, registry, http } = fixture();
  const selected = await registry.invoke(
    { id: 'select', code: 'rules.select-module', input: { moduleAlias: 'demo.order' } },
    registry.snapshot()!.token,
    undefined,
    { readOnly: true },
  );
  expect(selected.contextChanged).toBe(false);
  expect(selected.readStateChanged).toBe(true);
  const page = await registry.invoke(
    { id: 'read', code: 'rules.describe', input: { section: 'childFields', keyword: '小计' } },
    registry.snapshot()!.token,
    undefined,
    { readOnly: true },
  );
  expect(page.value).toMatchObject({ coverage: 'complete', total: 1 });
  expect(workspace.session('demo.order').dirty.value).toBe(false);
  expect(http.request).not.toHaveBeenCalledWith(
    expect.objectContaining({ path: expect.stringMatching(/\/(apply|preview)$/) }),
  );
  await expect(
    registry.invoke(
      { id: 'revise', code: 'rules.revise', input: {} },
      registry.snapshot()!.token,
      undefined,
      { readOnly: true },
    ),
  ).rejects.toThrow();
});

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

it('derives saved and draft rule facts from the shared baseline after manual edits and discard', async () => {
  const { workspace, invoke, rule, http } = fixture();
  const request = vi.mocked(http.request).getMockImplementation()!;
  vi.mocked(http.request).mockImplementation(async (options) => {
    const value = await request(options);
    if (options.path.endsWith('/business-rules'))
      return { ...(value as object), rules: [{ ...rule, editable: true, phase: 'BEFORE_SAVE' }] } as never;
    return value;
  });
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  const session = workspace.session('demo.order');
  expect(session.adapter.summary().hasUnappliedChanges).toBe(false);
  expect(session.adapter.catalog('rules')).toEqual([
    expect.objectContaining({ code: rule.code, persistence: 'saved' }),
  ]);
  session.replace([{ ...rule, expression: '{amount} > 0' }]);
  expect(session.adapter.summary().hasUnappliedChanges).toBe(true);
  expect(session.adapter.catalog('rules')).toEqual([
    expect.objectContaining({ code: rule.code, persistence: 'draft' }),
  ]);
  session.discard();
  expect(session.adapter.summary().hasUnappliedChanges).toBe(false);
  expect(session.adapter.catalog('rules')).toEqual([
    expect.objectContaining({ code: rule.code, expression: rule.expression, persistence: 'saved' }),
  ]);
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
  expect(workspace.current().facts.ruleConfiguration?.facts).toMatchObject({ factsAvailable: false });
  expect(workspace.current().facts.ruleConfiguration?.facts).not.toHaveProperty('fields');
  await expect(invoke('rules.describe', { section: 'fields' })).rejects.toThrow();
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

it('refreshes clean rules from governance without discarding an open manual editor', async () => {
  const { workspace, invoke, http } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  const session = workspace.session('demo.order');
  vi.mocked(http.request).mockImplementation(async (options) => {
    if (options.path.endsWith('/ui-controls'))
      return { baselineFingerprint: 'ui', forms: [], rules: [] } as never;
    return { ...session.snapshot.value, baselineFingerprint: 'changed-by-another-user' } as never;
  });
  await invoke('rules.select-module', { moduleAlias: 'demo.order', refresh: true });
  expect(session.snapshot.value?.baselineFingerprint).toBe('changed-by-another-user');
  const editor = Symbol('manual');
  session.setEditing(editor, true);
  const requests = vi.mocked(http.request).mock.calls.length;
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  expect(vi.mocked(http.request).mock.calls.length).toBe(requests);
  expect(session.editing.value).toBe(true);
});

it('does not reload or announce progress when reselecting the active rule workspace', async () => {
  const { invoke, http } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  const requests = vi.mocked(http.request).mock.calls.length;
  expect((await invoke('rules.select-module', { moduleAlias: 'demo.order' })).contextChanged).toBe(false);
  expect(vi.mocked(http.request).mock.calls.length).toBe(requests);
});

it('waits for a mounted editor to finish loading before continuing navigation', async () => {
  const { workspace, invoke, openRules, settleNavigation } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  const session = workspace.session('demo.order');
  openRules.mockImplementation(() => {
    session.loading.value = true;
    workspace.showEditor(session);
  });
  const opening = invoke('rules.open-editor');
  await Promise.resolve();
  expect(settleNavigation).not.toHaveBeenCalled();
  session.loading.value = false;
  await opening;
  expect(settleNavigation).toHaveBeenCalledOnce();
});

it('retains a committed receipt when UI synchronization fails and requires a fresh baseline', async () => {
  const { workspace, invoke, rule, http } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  await invoke('rules.revise', rule);
  const session = workspace.session('demo.order');
  const prepared = await invoke('rules.prepare-apply');
  const original = vi.mocked(http.request).getMockImplementation()!;
  let committed = false;
  let syncFails = true;
  const saved = {
    ...session.snapshot.value!,
    baselineFingerprint: 'committed',
    rules: [{ ...rule, phase: 'BEFORE_SAVE', editable: true }],
  };
  vi.mocked(http.request).mockImplementation(async (options) => {
    if (options.path.endsWith('/apply')) {
      committed = true;
      return { snapshot: saved, activatedModules: ['demo.order'] } as never;
    }
    if (committed && options.path.endsWith('/ui-controls') && syncFails) throw new Error('read unavailable');
    if (committed && options.path.endsWith('/business-rules')) return saved as never;
    return original(options);
  });
  await prepared.confirmation!.confirm();
  expect(prepared.confirmation!.state).toBe('succeeded');
  expect(prepared.confirmation!.result?.lines.join('；')).toContain('规则已提交');
  expect(session.committedNeedsReload.value).toBe(true);
  expect(session.ready.value).toBe(false);
  expect(session.dirty.value).toBe(false);
  expect(workspace.current().facts.ruleConfiguration?.facts).toMatchObject({ factsAvailable: false });
  expect(workspace.current().facts.ruleConfiguration?.facts).not.toHaveProperty('rules');
  await prepared.confirmation!.confirm();
  await expect(session.apply()).rejects.toThrow();
  await expect(session.load()).rejects.toThrow('read unavailable');
  expect(session.committedNeedsReload.value).toBe(true);
  expect(
    vi.mocked(http.request).mock.calls.filter(([request]) => request.path.endsWith('/apply')),
  ).toHaveLength(1);
  syncFails = false;
  await session.load();
  expect(session.snapshot.value?.baselineFingerprint).toBe('committed');
  expect(session.committedNeedsReload.value).toBe(false);
  expect(session.ready.value).toBe(true);
  expect(session.dirty.value).toBe(false);
});

it.each(['validation', 'missing-fingerprint', 'transport'])(
  'classifies a %s failure before apply as not submitted',
  async (failure) => {
    const { workspace, invoke, rule, http } = fixture();
    await invoke('rules.select-module', { moduleAlias: 'demo.order' });
    await invoke('rules.revise', rule);
    const session = workspace.session('demo.order');
    const original = vi.mocked(http.request).getMockImplementation()!;
    vi.mocked(http.request).mockImplementation(async (options) => {
      if (options.path.endsWith('/preview')) {
        if (failure === 'transport') throw new Error('preview unavailable');
        return {
          errors: failure === 'validation' ? [{ message: '规则不合法' }] : [],
          proposalFingerprint: failure === 'missing-fingerprint' ? undefined : 'checked',
        } as never;
      }
      return original(options);
    });
    await expect(session.apply()).rejects.toThrow();
    expect(session.submissionStatus.value).toBe('idle');
    expect(vi.mocked(http.request).mock.calls.some(([request]) => request.path.endsWith('/apply'))).toBe(
      false,
    );
  },
);

it('keeps a failed write response unknown and never resubmits while checking', async () => {
  const { invoke, rule, http } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  await invoke('rules.revise', rule);
  const prepared = await invoke('rules.prepare-apply');
  const original = vi.mocked(http.request).getMockImplementation()!;
  vi.mocked(http.request).mockImplementation(async (options) => {
    if (options.path.endsWith('/apply')) throw new Error('response lost');
    return original(options);
  });
  await prepared.confirmation!.confirm();
  expect(prepared.confirmation!.state).toBe('unknown');
  await prepared.confirmation!.check();
  await prepared.confirmation!.confirm();
  expect(prepared.confirmation!.state).toBe('unknown');
  expect(
    vi.mocked(http.request).mock.calls.filter(([request]) => request.path.endsWith('/apply')),
  ).toHaveLength(1);
});

it('retains commitment if the confirmation context changes while reading controls', async () => {
  const { workspace, invoke, rule, http } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  await invoke('rules.revise', rule);
  const prepared = await invoke('rules.prepare-apply');
  const session = workspace.session('demo.order');
  const original = vi.mocked(http.request).getMockImplementation()!;
  vi.mocked(http.request).mockImplementation(async (options) => {
    if (options.path.endsWith('/ui-controls')) session.invalidateConfirmations();
    return original(options);
  });
  await prepared.confirmation!.confirm();
  expect(prepared.confirmation!.state).toBe('succeeded');
  expect(session.committedNeedsReload.value).toBe(true);
  expect(session.ready.value).toBe(false);
});

it.each(['manual', 'assistant'] as const)(
  'shares unknown %s submissions and reads a new baseline without replay',
  async (entry) => {
    const { workspace, invoke, rule, http } = fixture();
    await invoke('rules.select-module', { moduleAlias: 'demo.order' });
    await invoke('rules.revise', rule);
    const session = workspace.session('demo.order');
    const prepared = await invoke('rules.prepare-apply');
    const original = vi.mocked(http.request).getMockImplementation()!;
    vi.mocked(http.request).mockImplementation(async (options) => {
      if (options.path.endsWith('/apply')) throw new Error('response lost');
      return original(options);
    });
    if (entry === 'manual') await expect(session.apply()).rejects.toThrow('response lost');
    else await prepared.confirmation!.confirm();
    expect(session.ready.value).toBe(false);
    expect(session.submissionStatus.value).toBe('unknown');
    expect(session.rules.value).toEqual([rule]);
    await expect(session.apply()).rejects.toThrow('结果未知');
    expect(() => session.discard()).toThrow('结果未知');
    await expect(invoke('rules.prepare-apply')).rejects.toThrow();
    await session.load(true);
    expect(session.submissionStatus.value).toBe('unknown');
    // Current state may include this candidate, but is not evidence for the original request.
    vi.mocked(http.request).mockImplementation(async (options) => {
      if (options.path.endsWith('/business-rules'))
        return {
          ...session.snapshot.value!,
          baselineFingerprint: 'new-baseline',
          rules: [{ ...rule, phase: 'BEFORE_SAVE', editable: true }],
        } as never;
      return original(options);
    });
    const read = await invoke('rules.read-current');
    expect(read.value).toMatchObject({ originalSubmission: 'unknown', currentConfigurationRead: true });
    expect(session.snapshot.value?.baselineFingerprint).toBe('new-baseline');
    expect(session.submissionStatus.value).toBe('current-read');
    expect(session.ready.value).toBe(true);
    expect(session.rules.value).toEqual([rule]);
    expect(session.dirty.value).toBe(false);
    if (entry === 'assistant') expect(prepared.confirmation!.state).toBe('unknown');
    expect(
      vi.mocked(http.request).mock.calls.filter(([request]) => request.path.endsWith('/apply')),
    ).toHaveLength(1);
  },
);

it('does not release an unknown submission when reading current rules fails', async () => {
  const { workspace, invoke, rule, http } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  await invoke('rules.revise', rule);
  const session = workspace.session('demo.order');
  const original = vi.mocked(http.request).getMockImplementation()!;
  vi.mocked(http.request).mockImplementation(async (options) => {
    if (options.path.endsWith('/apply') || options.path.endsWith('/business-rules'))
      throw new Error('unavailable');
    return original(options);
  });
  await expect(session.apply()).rejects.toThrow();
  await expect(session.readCurrent()).rejects.toThrow();
  expect(session.submissionStatus.value).toBe('unknown');
  expect(session.rules.value).toEqual([rule]);
  expect(session.snapshot.value?.baselineFingerprint).toBe('base');
});

it('submits the reviewed rule snapshot without another client precheck', async () => {
  const { invoke, rule, http } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  await invoke('rules.revise', rule);
  const proposal = await invoke('rules.prepare-apply');
  await proposal.confirmation!.confirm();
  expect(proposal.confirmation!.state).toBe('succeeded');
  expect(
    vi.mocked(http.request).mock.calls.filter(([request]) => request.path.endsWith('/preview')),
  ).toHaveLength(1);
  expect(
    vi.mocked(http.request).mock.calls.find(([request]) => request.path.endsWith('/apply'))?.[0].body,
  ).toMatchObject({ baselineFingerprint: 'base', proposalFingerprint: 'checked', rules: [rule] });
});

it('reselects an unknown rule session and hands off to its recovery page without waiting for writability', async () => {
  const { workspace, invoke, rule, http, openRules, settleNavigation } = fixture();
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  await invoke('rules.revise', rule);
  const session = workspace.session('demo.order');
  const original = vi.mocked(http.request).getMockImplementation()!;
  vi.mocked(http.request).mockImplementation(async (options) => {
    if (options.path.endsWith('/apply')) throw new Error('lost');
    if (options.path.includes('demo.other') && !options.path.endsWith('/ui-controls'))
      return { ...session.snapshot.value!, moduleAlias: 'demo.other' } as never;
    return original(options);
  });
  await expect(session.apply()).rejects.toThrow();
  await invoke('rules.select-module', { moduleAlias: 'demo.other' });
  await invoke('rules.select-module', { moduleAlias: 'demo.order' });
  expect(workspace.editor()?.moduleAlias).toBe('demo.order');
  expect(workspace.capabilities(settleNavigation).map((item) => item.descriptor.code)).toContain(
    'rules.read-current',
  );
  await invoke('rules.open-editor');
  expect(openRules).toHaveBeenCalledWith('demo.order');
  expect(settleNavigation).toHaveBeenCalledOnce();
  expect(session.ready.value).toBe(false);
  expect(session.submissionStatus.value).toBe('unknown');
});
