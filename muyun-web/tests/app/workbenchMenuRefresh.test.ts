import { expect, it, vi } from 'vitest';
import type { MenuTreeNode } from '@/web-contracts';
import { createWorkbenchMenuRefresh } from '@/app/workbenchMenuRefresh';

function menu(id: string, title: string): MenuTreeNode[] {
  return [{ record: { id, schemeId: 'default', title, enabled: true }, children: [] }];
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (cause: unknown) => void;
  const promise = new Promise<T>((accept, fail) => {
    resolve = accept;
    reject = fail;
  });
  return { promise, resolve, reject };
}

function fixture() {
  let identity = 'session-a';
  let ready = true;
  let menus = menu('initial', '原入口');
  const reads: ReturnType<typeof deferred<MenuTreeNode[]>>[] = [];
  const installRoutes = vi.fn<(menus: MenuTreeNode[]) => Promise<void>>(async () => {});
  const commit = vi.fn((records: MenuTreeNode[]) => (menus = records));
  const owner = createWorkbenchMenuRefresh({
    ready: () => ready,
    identity: () => identity,
    menus: () => menus,
    read: () => {
      const read = deferred<MenuTreeNode[]>();
      reads.push(read);
      return read.promise;
    },
    installRoutes,
    commit,
  });
  return {
    owner,
    reads,
    installRoutes,
    commit,
    menus: () => menus,
    identity: (value: string) => (identity = value),
    ready: (value: boolean) => (ready = value),
  };
}

it('keeps the newest shared explicit/realtime read when responses arrive in reverse order', async () => {
  const f = fixture();
  const first = f.owner.refresh();
  const second = f.owner.refresh();
  const latest = menu('latest', '新增入口');
  f.reads[1]!.resolve(latest);
  await expect(second).resolves.toEqual(latest);
  f.reads[0]!.resolve(menu('obsolete', '过期入口'));
  await expect(first).resolves.toEqual(latest);
  expect(f.installRoutes).toHaveBeenCalledExactlyOnceWith(latest);
  expect(f.commit).toHaveBeenCalledExactlyOnceWith(latest);
});

it.each(['identity', 'same-user-login', 'dispose'] as const)(
  'does not touch shared routes after %s invalidates the reader',
  async (change) => {
    const f = fixture();
    const pending = f.owner.refresh();
    if (change === 'identity') f.identity('session-b');
    if (change === 'same-user-login') {
      // Logout/relogin may restore the same displayed identity; its generation still changes.
      f.owner.invalidate();
    }
    if (change === 'dispose') f.owner.dispose();
    f.reads[0]!.resolve(menu('old', '旧身份入口'));
    await expect(pending).resolves.toEqual(f.menus());
    expect(f.installRoutes).not.toHaveBeenCalled();
    expect(f.commit).not.toHaveBeenCalled();
  },
);

it('checks identity again after route installation before changing startup state', async () => {
  const f = fixture();
  const installed = deferred<void>();
  f.installRoutes.mockImplementation(() => installed.promise);
  const pending = f.owner.refresh();
  f.reads[0]!.resolve(menu('old', '旧菜单'));
  await Promise.resolve();
  expect(f.installRoutes).toHaveBeenCalledTimes(1);
  f.owner.invalidate();
  f.ready(false);
  installed.resolve();
  await expect(pending).resolves.toEqual(f.menus());
  expect(f.commit).not.toHaveBeenCalled();
});

it('silences obsolete failed reads but propagates a current failure to the caller', async () => {
  const f = fixture();
  const obsolete = f.owner.refresh();
  const current = f.owner.refresh();
  f.reads[0]!.reject(new Error('old transport failure'));
  await expect(obsolete).resolves.toEqual(f.menus());
  f.reads[1]!.reject(new Error('current transport failure'));
  await expect(current).rejects.toThrow('current transport failure');
  expect(f.installRoutes).not.toHaveBeenCalled();
});

it('avoids reads when the owner is not ready or has been disposed', async () => {
  const f = fixture();
  f.ready(false);
  await f.owner.refresh();
  f.ready(true);
  f.owner.dispose();
  await f.owner.refresh();
  expect(f.reads).toHaveLength(0);
});
