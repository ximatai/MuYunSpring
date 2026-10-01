import { presentPlatformError } from '@muyun/platform-components';
import { usePageDataChange, useRealtimeRefreshQueue } from '../platform-admin-runtime/pageRealtime';

export function useWorkbenchMenuRefresh(options: { ready: () => boolean; refresh: () => Promise<unknown> }) {
  const queue = useRealtimeRefreshQueue({
    async load(run) {
      if (!options.ready()) return;
      try {
        await options.refresh();
      } catch (cause) {
        if (run.active() && options.ready()) {
          presentPlatformError(cause, { source: 'workbench-menu', phase: 'load' });
        }
      }
    },
  });
  for (const moduleAlias of ['platform.menu', 'platform.menu_scheme']) {
    usePageDataChange({
      moduleAlias,
      handler: () => {
        if (options.ready()) queue.enqueue('menus');
      },
    });
  }
}
