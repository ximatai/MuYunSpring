import { ref } from 'vue';
import type { PageBootstrap } from '@muyun/web-contracts';
import { createPageBootstrapClient, type ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';

/**
 * Owns the menu-entry bootstrap session for a standard module page.
 *
 * A host may be rendered without a persisted menu entry (for example from a
 * static route). In that case no request is issued and `ready` is immediately
 * true. A failed entry lookup is intentionally stateful: it is rendered by the
 * host instead of allowing the rest of the page session to start with an
 * unverified module alias.
 */
export function useModulePageBootstrap(
  context: ModuleContext<QueryListRecord>,
  menuId: () => string | undefined,
) {
  const pageBootstrap = ref<PageBootstrap>();
  const pageBootstrapError = ref<string>();

  async function loadPageBootstrap() {
    const entryMenuId = menuId();
    if (!entryMenuId) return;
    try {
      const bootstrap = await createPageBootstrapClient(context.http).byMenu(entryMenuId);
      if (bootstrap.entry.moduleAlias !== context.moduleAlias) {
        throw new Error(
          `Menu ${entryMenuId} resolves ${bootstrap.entry.moduleAlias}, expected ${context.moduleAlias}`,
        );
      }
      pageBootstrap.value = bootstrap;
      pageBootstrapError.value = undefined;
    } catch (cause) {
      pageBootstrapError.value = cause instanceof Error ? cause.message : '页面入口加载失败';
    }
  }

  return {
    pageBootstrap,
    pageBootstrapError,
    loadPageBootstrap,
  };
}
