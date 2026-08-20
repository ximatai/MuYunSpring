import { ref, toRaw } from 'vue';
import type { QueryListRecord } from '@muyun/platform-components';
import type { ModuleContext } from '@muyun/web-core';
import type {
  ModulePageDetailSectionContext,
  ModulePageDrawer,
  ModulePageDrawerAction,
  ModulePageDrawerContext,
  ModulePageRecordViewContext,
  ModulePageScopeContext,
} from '../modulePageEnhancements';

export interface ModulePageEnhancementDrawerRuntime {
  definition: ModulePageDrawer;
  context: ModulePageDrawerContext;
  titleActions: ModulePageDrawerAction[];
  closeBlocked: boolean;
}

interface Options {
  module: ModuleContext<QueryListRecord>;
  scope(): ModulePageScopeContext | undefined;
  refreshList(): void;
  reload(): void;
  closeDetail(): void;
}

/** Owns frontend-only detail extension invalidation and drawer lifecycle. */
export function useModulePageDetailExtensionRuntime(options: Options) {
  const refreshKey = ref(0);
  const drawer = ref<ModulePageEnhancementDrawerRuntime>();

  function refreshDetailExtensions() {
    refreshKey.value += 1;
  }

  function sectionContext(record: QueryListRecord): ModulePageDetailSectionContext {
    return {
      module: options.module,
      record,
      refreshList: options.refreshList,
      refreshKey: refreshKey.value,
      reload: options.reload,
    };
  }

  function recordViewContext(record: QueryListRecord): ModulePageRecordViewContext {
    return {
      module: options.module,
      record,
      scope: options.scope(),
      refreshList: options.refreshList,
      refreshDetailExtensions,
      close: options.closeDetail,
      reload: options.reload,
    };
  }

  function openDrawer(definition: ModulePageDrawer, record?: QueryListRecord) {
    const runtime = {
      definition,
      titleActions: [] as ModulePageDrawerAction[],
      closeBlocked: false,
      context: undefined as unknown as ModulePageDrawerContext,
    };
    const drawerContext: ModulePageDrawerContext = {
      module: options.module,
      record,
      scope: options.scope(),
      refreshList: options.refreshList,
      refreshDetailExtensions,
      setCloseBlocked(blocked) {
        const activeDrawer = drawer.value;
        if (activeDrawer && toRaw(activeDrawer.context) === drawerContext) {
          activeDrawer.closeBlocked = blocked;
        }
      },
      close: () => {
        drawer.value = undefined;
      },
      reload: options.reload,
      setTitleActions(actions) {
        const activeDrawer = drawer.value;
        if (activeDrawer && toRaw(activeDrawer.context) === drawerContext) {
          activeDrawer.titleActions = actions;
        }
      },
    };
    runtime.context = drawerContext;
    drawer.value = runtime;
  }

  function closeDrawer() {
    if (!drawer.value?.closeBlocked) drawer.value = undefined;
  }

  return { drawer, refreshDetailExtensions, sectionContext, recordViewContext, openDrawer, closeDrawer };
}
