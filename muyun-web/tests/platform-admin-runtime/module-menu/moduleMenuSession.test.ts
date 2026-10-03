import { describe, expect, it, vi } from 'vitest';
import { AppError } from '@/web-core';
import {
  createModuleMenuSession,
  availableDirectories,
  type ModuleMenuClient,
  type ModuleMenuReceiptStore,
} from '@/platform-admin-runtime/module-menu/moduleMenuSession';
import type { MenuRecord, MenuTreeNode, MenuScheme, OperationReceiptReference } from '@/web-contracts';

function setup(receipts?: ModuleMenuReceiptStore) {
  let valid = true;
  let active = true;
  const client = {
    schemes: vi.fn(async (): Promise<MenuScheme[]> => [{ id: 'a', title: '业务菜单', enabled: true }]),
    tree: vi.fn(async () => [] as MenuTreeNode[]),
    context: vi.fn(async () => ({
      title: '客户',
      actions: [{ actionCode: 'create', authorized: true }],
      uiDescriptor: { page: { template: 'management' } },
    })),
    visible: vi.fn(async () => [] as MenuTreeNode[]),
    insert: vi.fn(async (...args: [string, MenuRecord, string]) => ({
      record: { ...args[1], id: 'created' },
    })),
    view: vi.fn(
      async () =>
        ({ id: 'created', moduleAlias: 'crm.customer', schemeId: 'a', title: '已保存客户' }) as MenuRecord,
    ),
    receipt: vi.fn(async () => ({ committed: true, recordId: 'created' })),
  };
  const session = createModuleMenuSession(
    client as unknown as ModuleMenuClient,
    'crm.customer',
    {
      valid: () => valid,
      active: () => active,
    },
    receipts ?? { restore: async () => undefined, save: async () => {}, clear: async () => {} },
    client.visible,
  );
  return {
    session,
    client,
    invalidate: () => {
      valid = false;
    },
    deactivate: () => {
      active = false;
    },
  };
}

describe('shared standard menu candidate', () => {
  it('invalidates prepared confirmation after manual revision or leaving the page', async () => {
    const t = setup();
    await t.session.load();
    const first = await t.session.prepare();
    await t.session.update({ title: '我的客户' });
    expect(first.isCurrent()).toBe(false);
    await expect(first.execute()).rejects.toThrow('已变化');
    const second = await t.session.prepare();
    t.deactivate();
    await expect(second.execute()).rejects.toThrow('已变化');
    expect(t.client.insert).not.toHaveBeenCalled();
  });

  it('revalidates permissions immediately before posting and allows correction after proven rejection', async () => {
    const t = setup();
    await t.session.load();
    const proposal = await t.session.prepare();
    t.client.context.mockResolvedValueOnce({
      title: '客户',
      actions: [],
      uiDescriptor: { page: { template: 'management' } },
    });
    t.client.context.mockResolvedValueOnce({
      title: '菜单',
      actions: [],
      uiDescriptor: { page: { template: 'management' } },
    });
    await expect(proposal.execute()).rejects.toThrow('没有添加菜单入口权限');
    expect(t.client.insert).not.toHaveBeenCalled();
    expect(t.session.resultUnknown.value).toBe(false);
    t.client.insert.mockRejectedValueOnce(new AppError('名称无效', { status: 400 }));
    await expect((await t.session.prepare()).execute()).rejects.toThrow('名称无效');
    expect(t.session.canSave.value).toBe(true);
  });

  it('presents freshly read scheme scope and rejects a later audience change', async () => {
    const t = setup();
    await t.session.load();
    const fresh: MenuScheme = {
      id: 'a',
      title: '工作人员',
      scopeType: 'tenant',
      tenantId: 'demo',
      enabled: true,
    };
    t.client.schemes.mockResolvedValue([fresh]);
    const proposal = await t.session.prepare();
    expect(proposal.presentation.lines).toContain('适用范围：租户 demo 的用户');
    expect(proposal.presentation.lines[0]).toBe('工作人员 / 客户');
    t.client.schemes.mockResolvedValue([{ ...fresh, scopeType: 'organization', organizationId: 'org-b' }]);
    await expect(proposal.execute()).rejects.toThrow('适用范围或目录已变化');
    expect(t.client.insert).not.toHaveBeenCalled();
  });

  it('rejects changed parent ancestry even when the directory itself stays enabled', async () => {
    const t = setup();
    const directory = { record: { id: 'd', schemeId: 'a', title: '登记' }, children: [] };
    t.client.tree.mockResolvedValue([directory]);
    await t.session.load();
    await t.session.update({ parentId: 'd' });
    const proposal = await t.session.prepare();
    t.client.tree.mockResolvedValue([
      { record: { id: 'other', schemeId: 'a', title: '新位置' }, children: [directory] },
    ]);
    await expect(proposal.execute()).rejects.toThrow('适用范围或目录已变化');
    expect(t.client.insert).not.toHaveBeenCalled();
  });

  it('uses the original durable request after transport loss and never posts another insert', async () => {
    const t = setup();
    await t.session.load();
    t.client.insert.mockRejectedValueOnce(new Error('connection lost'));
    const proposal = await t.session.prepare();
    await expect(proposal.execute()).rejects.toThrow('connection lost');
    const requestId = t.client.insert.mock.calls[0][2];
    expect(proposal.receiptReference).toMatchObject({
      kind: 'record-save',
      requestId,
      pageContext: { scheme: 'a' },
    });
    expect(t.session.resultUnknown.value).toBe(true);
    await expect(t.session.prepare()).rejects.toThrow('查询未确定');
    await t.session.lookupPending();
    expect(t.client.receipt).toHaveBeenCalledWith('a', requestId);
    expect(t.session.saved.value?.id).toBe('created');
    expect(t.session.resultUnknown.value).toBe(false);
    expect(t.client.insert).toHaveBeenCalledOnce();
  });

  it('keeps an unconfirmed original receipt unknown rather than inferring success by title', async () => {
    const t = setup();
    await t.session.load();
    t.client.insert.mockRejectedValueOnce(new Error('timeout'));
    await expect((await t.session.prepare()).execute()).rejects.toThrow('timeout');
    t.client.receipt.mockResolvedValueOnce({ committed: false, recordId: '' });
    expect(await t.session.lookupPending()).toBeUndefined();
    expect(t.session.resultUnknown.value).toBe(true);
    expect(t.session.saved.value).toBeUndefined();
  });

  it('distinguishes persisted, hidden and navigation-refresh failure without permitting a duplicate', async () => {
    const t = setup();
    await t.session.load();
    t.client.visible.mockRejectedValueOnce(new Error('offline'));
    const result = await (await t.session.prepare()).execute();
    expect(result.title).toBe('菜单入口已保存');
    expect(t.session.saved.value?.id).toBe('created');
    expect(t.session.error.value).toContain('无需重复添加');
    expect(t.session.canSave.value).toBe(false);
    await t.session.refreshVisibility();
    expect(t.session.visibilityChecked.value).toBe(true);
    expect(t.session.visibleMenu.value).toBeUndefined();
    expect(t.client.insert).toHaveBeenCalledOnce();
  });

  it('rejects a parent outside the selected scheme without changing the existing candidate', async () => {
    const t = setup();
    await t.session.load();
    t.client.schemes.mockResolvedValueOnce([
      { id: 'a', title: '菜单A', enabled: true },
      { id: 'b', title: '菜单B', enabled: true },
    ]);
    await t.session.load();
    await expect(t.session.planRevision({ schemeId: 'b', parentId: 'other', title: '变更' })).rejects.toThrow(
      '启用目录',
    );
    expect(t.session.schemeId.value).toBe('a');
    expect(t.session.title.value).toBe('客户');
  });

  it('restores only the original read-only identity across reload and never restores a write payload', async () => {
    let stored: OperationReceiptReference | undefined;
    const store: ModuleMenuReceiptStore = {
      restore: async () => stored,
      save: async (value) => {
        stored = structuredClone(value);
      },
      clear: async () => {
        stored = undefined;
      },
    };
    const first = setup(store);
    await first.session.load();
    first.client.insert.mockRejectedValueOnce(new Error('lost response'));
    await expect((await first.session.prepare()).execute()).rejects.toThrow('lost response');
    expect(stored).toMatchObject({
      kind: 'record-save',
      moduleAlias: 'platform.menu',
      pageContext: { scheme: 'a' },
    });
    expect(stored).not.toHaveProperty('record');
    const reopened = setup(store);
    await reopened.session.load();
    expect(reopened.session.resultUnknown.value).toBe(true);
    expect(reopened.session.canSave.value).toBe(false);
    await reopened.session.lookupPending();
    expect(reopened.client.view).toHaveBeenCalledWith('a', 'created');
    expect(reopened.session.saved.value?.title).toBe('已保存客户');
    expect(stored).toBeUndefined();
    expect(reopened.client.insert).not.toHaveBeenCalled();
  });

  it('refuses to post if the recovery identity cannot be persisted', async () => {
    const store: ModuleMenuReceiptStore = {
      restore: async () => undefined,
      save: async () => {
        throw new Error('cannot persist recovery identity');
      },
      clear: async () => {},
    };
    const t = setup(store);
    await t.session.load();
    await expect((await t.session.prepare()).execute()).rejects.toThrow('cannot persist');
    expect(t.client.insert).not.toHaveBeenCalled();
    expect(t.session.resultUnknown.value).toBe(false);
  });

  it('can clear a proven unsubmitted request after the persistence service recovers', async () => {
    const clear = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined);
    const t = setup({
      restore: async () => undefined,
      save: async () => {
        throw new Error('offline');
      },
      clear,
    });
    await t.session.load();
    await expect((await t.session.prepare()).execute()).rejects.toThrow('offline');
    expect(t.session.resultUnknown.value).toBe(true);
    await t.session.lookupPending();
    expect(t.session.resultUnknown.value).toBe(false);
    expect(t.session.canSave.value).toBe(true);
    expect(t.client.receipt).not.toHaveBeenCalled();
    expect(t.client.insert).not.toHaveBeenCalled();
  });

  it('allows an explicitly requested second entry only after the first result is known', async () => {
    const t = setup();
    await t.session.load();
    await expect(t.session.startAnother()).rejects.toThrow('核实');
    await (await t.session.prepare()).execute();
    await t.session.startAnother();
    expect(t.session.saved.value).toBeUndefined();
    expect(t.session.canSave.value).toBe(true);
    const second = await t.session.prepare();
    await second.execute();
    expect(t.client.insert).toHaveBeenCalledTimes(2);
    expect(t.client.insert.mock.calls[0][2]).not.toBe(t.client.insert.mock.calls[1][2]);
  });

  it('discards late loads after identity invalidation', async () => {
    const t = setup();
    let finish!: (value: MenuTreeNode[]) => void;
    t.client.tree.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const loading = t.session.load();
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'));
    t.invalidate();
    finish([]);
    await loading;
    expect(t.session.ready.value).toBe(false);
    expect(t.client.insert).not.toHaveBeenCalled();
  });

  it('excludes disabled ancestors and module nodes as placement targets', () => {
    const tree = [
      {
        record: { schemeId: 'a', id: 'a', title: '停用', enabled: false },
        children: [{ record: { schemeId: 'a', id: 'b', title: '子目录' }, children: [] }],
      },
      { record: { schemeId: 'a', id: 'c', title: '业务', moduleAlias: 'crm.customer' }, children: [] },
      { record: { schemeId: 'a', id: 'd', title: '目录' }, children: [] },
    ] as MenuTreeNode[];
    expect(availableDirectories(tree).map((value) => value.menu.id)).toEqual(['d']);
  });
});
