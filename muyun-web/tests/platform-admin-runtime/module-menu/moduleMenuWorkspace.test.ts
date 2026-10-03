import { describe, expect, it, vi } from 'vitest';
import { createModuleMenuWorkspace } from '@/platform-admin-runtime/module-menu/moduleMenuWorkspace';
import type { AssistantCapabilityExecutionContext } from '@/web-core';

function setup(authorized = true) {
  let identity = 'ordinary-user';
  const request = vi.fn(async ({ path }: { path: string }) => {
    if (path.endsWith('/context'))
      return {
        title: '报名登记',
        actions: [{ actionCode: 'create', authorized }],
        uiDescriptor: { page: { template: 'management' } },
      };
    if (path === '/platform.menu_scheme/query')
      return {
        records: [{ id: 'business-scheme-id', title: '业务菜单', scopeType: 'tenant', tenantId: 'demo' }],
        total: 1,
      };
    if (path === '/platform.menu/tree/query' || path === '/platform.menu/mine') return { records: [] };
    throw new Error(path);
  });
  const workspace = createModuleMenuWorkspace(
    { request: request as never },
    () => identity,
    () => true,
    vi.fn(),
    undefined,
    undefined,
    {
      get: (_key, fallback) => fallback,
      restore: async (_key, fallback) => fallback,
      set: async () => {},
      remove: async () => {},
    },
    { getItem: () => null, setItem: () => {}, removeItem: () => {} },
  );
  const context: AssistantCapabilityExecutionContext = {
    signal: new AbortController().signal,
    isCurrent: () => true,
    applyEffect: (effect) => effect(),
    commitInternalState: (effect) => effect(),
  };
  const capabilities = () => workspace.capabilities(async () => {});
  const capability = (code: string) => capabilities().find((item) => item.descriptor.code === code)!;
  return {
    workspace,
    request,
    context,
    capabilities,
    capability,
    identity: (value: string) => {
      identity = value;
    },
  };
}

async function openCandidate(t: ReturnType<typeof setup>) {
  t.workspace.current();
  await vi.waitFor(() => expect(t.capability('configuration.open-menu-editor')).toBeDefined());
  const session = t.workspace.session('club.registration');
  await session.load();
  t.workspace.focus(session);
  t.workspace.showEditor(session);
  return session;
}

describe('thin menu assistant adapter', () => {
  it('uses actual create authorization rather than administrator status or a construction plan', async () => {
    const t = setup();
    const session = await openCandidate(t);
    expect(t.capability('configuration.open-menu-editor')).toBeDefined();
    const prepare = t.capability('menu.prepare-add');
    await prepare.execute(prepare.parseInput({}), t.context);
    expect(prepare.propose!({}).presentation.title).toBe('添加业务入口');
    expect(t.request.mock.calls.some(([call]) => call.path.endsWith('/insert'))).toBe(false);
    await session.update({ title: '周末报名' });
    expect(prepare.propose!({}).isCurrent()).toBe(false);
  });

  it('offers no menu write adapter without actual menu authorization', async () => {
    const t = setup(false);
    t.workspace.current();
    await Promise.resolve();
    await Promise.resolve();
    expect(t.capabilities()).toEqual([]);
  });

  it('accepts only issued selection keys and invalidates them when the shared candidate changes', async () => {
    const t = setup();
    const session = await openCandidate(t);
    const describe = t.capability('menu.describe');
    const result = (await describe.execute(describe.parseInput({}), t.context)) as {
      schemes: { selectionKey: string }[];
    };
    expect(result).toMatchObject({ audience: '租户 demo 的用户', truncated: false });
    expect(result).not.toHaveProperty('nextOffset');
    expect(result.schemes[0].selectionKey).not.toBe('business-scheme-id');
    const revise = t.capability('menu.revise-draft');
    expect(() => revise.parseInput({ schemeKey: 'business-scheme-id' })).toThrow('选择已失效');
    const patch = revise.parseInput({ schemeKey: result.schemes[0].selectionKey, title: '报名入口' });
    await session.update({ title: '手工修改' });
    await expect(revise.execute(patch, t.context)).rejects.toThrow('重新读取');
    expect(session.title.value).toBe('手工修改');
    const current = t.capability('menu.revise-draft');
    expect(() => current.parseInput({ schemeKey: result.schemes[0].selectionKey })).toThrow('选择已失效');
  });

  it('cannot confirm a shared candidate after its editor is hidden or identity changes', async () => {
    const t = setup();
    const session = await openCandidate(t);
    const proposal = await session.prepare();
    t.workspace.hideEditor(session);
    expect(proposal.isCurrent()).toBe(false);
    t.identity('other-user');
    t.workspace.current();
    await expect(proposal.execute()).rejects.toThrow('已变化');
  });
});
