import type { MenuTreeNode } from '@muyun/web-contracts';

/** One owner for explicit and realtime menu reads; obsolete reads never install routes. */
export function createWorkbenchMenuRefresh(options: {
  ready(): boolean;
  identity(): string;
  menus(): MenuTreeNode[];
  read(): Promise<MenuTreeNode[]>;
  installRoutes(menus: MenuTreeNode[]): Promise<void>;
  commit(menus: MenuTreeNode[]): void;
}) {
  let revision = 0;
  let disposed = false;
  function invalidate() {
    revision += 1;
  }
  return {
    invalidate,
    dispose() {
      disposed = true;
      invalidate();
    },
    async refresh(): Promise<MenuTreeNode[]> {
      const requestRevision = ++revision;
      const identity = options.identity();
      const current = () =>
        !disposed && requestRevision === revision && options.ready() && identity === options.identity();
      if (!current()) return options.menus();
      try {
        const records = await options.read();
        if (!current()) return options.menus();
        // The route runtime installs supplied menus synchronously. Check again after its Promise
        // settles so logout or a newer read cannot revive this owner's startup state.
        await options.installRoutes(records);
        if (!current()) return options.menus();
        options.commit(records);
        return records;
      } catch (cause) {
        if (!current()) return options.menus();
        throw cause;
      }
    },
  };
}
