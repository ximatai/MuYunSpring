import type { ModulePageEnhancement, ModulePageWorkspaceView } from '@muyun/dynamic-page-runtime';
import type { QueryListRecord } from '@muyun/platform-components';
import { moduleActionManagementWorkspaceView } from '../views/moduleActionManagementWorkspaceView';
import { moduleGovernanceWorkspaceView } from '../views/moduleGovernanceWorkspaceView';

// Workspace definitions use the platform-workbench's serializable input
// contract. The enhancement boundary exposes the equivalent dynamic-runtime
// contract, so adapt them once at this composition edge.
const moduleActionWorkspaceView = moduleActionManagementWorkspaceView as unknown as ModulePageWorkspaceView;
const moduleGovernanceWorkspace = moduleGovernanceWorkspaceView as unknown as ModulePageWorkspaceView;

/**
 * Frontend composition for the platform-module descriptor page.
 *
 * These actions deliberately reuse ordinary workspace/page descriptors. The
 * backend UI descriptor only describes the module record; it never chooses a
 * Vue component or arbitrary client-side behavior.
 */
export const platformModulePageEnhancement: ModulePageEnhancement = {
  id: 'platform-module-workspace-actions',
  target: { moduleAlias: 'platform.module' },
  // The hand-authored workspace remains an explicit extension for dynamic executor binding;
  // it has no menu identity and is not the general action-management entry.
  workspaceViews: [moduleGovernanceWorkspace, moduleActionWorkspaceView],
  detail: {
    actions: [
      {
        key: 'module-actions-workspace',
        title: '动作',
        state: (record) => ({ visible: moduleAliasOf(record) !== undefined }),
        run({ record, openWorkspaceTab }) {
          const moduleAlias = moduleAliasOf(record);
          if (!moduleAlias) return;
          openWorkspaceTab(moduleActionWorkspaceView, {
            moduleAlias,
            moduleTitle: titleOf(record),
            moduleKind: moduleKindOf(record),
          });
        },
      },
      {
        key: 'module-ui-orchestration-workspace',
        title: '低代码',
        state: (record) => ({
          visible: moduleAliasOf(record) !== undefined && moduleKindOf(record) === 'dynamic',
        }),
        run({ record, openWorkspaceTab }) {
          const moduleAlias = moduleAliasOf(record);
          if (!moduleAlias || moduleKindOf(record) !== 'dynamic') return;
          openWorkspaceTab(moduleGovernanceWorkspace, {
            moduleAlias,
            moduleTitle: titleOf(record),
            governanceTab: 'overview',
          });
        },
      },
    ],
  },
};

/**
 * Action management has no menu identity of its own. Its standard CRUD page is
 * entered from exactly one governed platform module, which is the hidden and
 * immutable navigator scope.
 */
export const platformModuleActionPageEnhancement: ModulePageEnhancement = {
  id: 'platform-module-action-entry-scope',
  target: { moduleAlias: 'platform.module_action' },
  navigator: {
    hidden: true,
    lockedEntry: {
      navigatorKey: 'module',
      unavailableDescription: '模块上下文不可用',
    },
  },
  // Manual action binding requires an executor registry and executor-specific level validation.
  // Keep the standard page declarative for browsing/governance; use the explicit dynamic-module
  // extension above for that specialised binding flow.
  standardActions: { disabled: ['create'] },
};

function moduleAliasOf(record: QueryListRecord): string | undefined {
  return stringField(record, 'alias') ?? stringField(record, 'id');
}

function titleOf(record: QueryListRecord): string | undefined {
  return stringField(record, 'title');
}

function moduleKindOf(record: QueryListRecord): 'static' | 'dynamic' | undefined {
  const value = stringField(record, 'moduleKind');
  return value === 'static' || value === 'dynamic' ? value : undefined;
}

function stringField(record: QueryListRecord, name: string): string | undefined {
  const value = record[name];
  return typeof value === 'string' && value.trim() ? value : undefined;
}
