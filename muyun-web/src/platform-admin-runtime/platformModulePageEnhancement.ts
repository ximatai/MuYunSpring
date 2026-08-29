import { ref } from 'vue';
import type { QueryListRecord } from '@muyun/platform-components';
import type { ModulePageEnhancement, ModulePageWorkspaceView } from '@muyun/dynamic-page-runtime';
import {
  NAVIGATOR_ENTRY_MODULE_ALIAS_QUERY_KEY,
  NAVIGATOR_ENTRY_RECORD_ID_QUERY_KEY,
} from '@muyun/dynamic-page-runtime';
import type { PageDescriptor } from '@muyun/web-contracts';
import { createModuleOpenApiPageDescriptor, loadOpenApiCatalog } from './moduleOpenApi';
import { moduleActionManagementWorkspaceView } from '../views/moduleActionManagementWorkspaceView';
import { metadataOrchestrationWorkspaceView } from '../views/metadataOrchestrationWorkspaceView';
import { uiOrchestrationWorkspaceView } from '../views/uiOrchestrationWorkspaceView';
import { moduleGovernanceWorkspaceView } from '../views/moduleGovernanceWorkspaceView';

const openApiModuleAliases = ref<ReadonlySet<string>>(new Set());
let openApiCatalogRevision = 0;
// Workspace definitions use the platform-workbench's serializable input
// contract. The enhancement boundary exposes the equivalent dynamic-runtime
// contract, so adapt them once at this composition edge.
const moduleActionWorkspaceView = moduleActionManagementWorkspaceView as unknown as ModulePageWorkspaceView;
const metadataWorkspaceView = metadataOrchestrationWorkspaceView as unknown as ModulePageWorkspaceView;
const uiOrchestrationWorkspace = uiOrchestrationWorkspaceView as unknown as ModulePageWorkspaceView;
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
  activate({ module }) {
    const revision = ++openApiCatalogRevision;
    // Never retain a previous session's catalog while the current identity is
    // being resolved. Visibility is only a convenience; the endpoint remains
    // authoritative, but stale presentation must not leak it.
    openApiModuleAliases.value = new Set();
    void loadOpenApiCatalog(module.http)
      .then((catalog) => {
        if (revision === openApiCatalogRevision) {
          openApiModuleAliases.value = new Set(catalog.map((item) => item.moduleAlias));
        }
      })
      .catch(() => {
        if (revision === openApiCatalogRevision) openApiModuleAliases.value = new Set();
      });
    return () => {
      if (revision === openApiCatalogRevision) openApiModuleAliases.value = new Set();
    };
  },
  // The hand-authored workspace remains an explicit extension for dynamic executor binding;
  // it has no menu identity and is not the general action-management entry.
  workspaceViews: [
    moduleGovernanceWorkspace,
    moduleActionWorkspaceView,
    metadataWorkspaceView,
    uiOrchestrationWorkspace,
  ],
  detail: {
    actions: [
      {
        key: 'module-governance-workspace',
        title: '模块治理',
        state: (record) => ({
          visible: moduleAliasOf(record) !== undefined && moduleKindOf(record) === 'dynamic',
        }),
        run({ record, openWorkspaceTab }) {
          const moduleAlias = moduleAliasOf(record);
          if (!moduleAlias || moduleKindOf(record) !== 'dynamic') return;
          openWorkspaceTab(moduleGovernanceWorkspace, {
            moduleAlias,
            moduleTitle: titleOf(record),
            governanceTab: 'metadata',
          });
        },
      },
      {
        key: 'module-actions-workspace',
        title: '动作',
        state: (record) => ({ visible: moduleAliasOf(record) !== undefined }),
        run({ record, openPage }) {
          const moduleAlias = moduleAliasOf(record);
          if (!moduleAlias) return;
          openPage(createModuleActionPageDescriptor(moduleAlias, titleOf(record)));
        },
      },
      {
        key: 'module-metadata-orchestration-workspace',
        title: '元数据编排',
        state: (record) => ({
          visible: moduleAliasOf(record) !== undefined && moduleKindOf(record) === 'dynamic',
        }),
        run({ record, openWorkspaceTab }) {
          const moduleAlias = moduleAliasOf(record);
          if (!moduleAlias || moduleKindOf(record) !== 'dynamic') return;
          openWorkspaceTab(metadataWorkspaceView, {
            moduleAlias,
            moduleTitle: titleOf(record),
          });
        },
      },
      {
        key: 'module-manual-action-binding-workspace',
        title: '自定义动作',
        state: (record) => ({
          visible: moduleAliasOf(record) !== undefined && moduleKindOf(record) === 'dynamic',
        }),
        run({ record, openWorkspaceTab }) {
          const moduleAlias = moduleAliasOf(record);
          if (!moduleAlias || moduleKindOf(record) !== 'dynamic') return;
          openWorkspaceTab(moduleActionWorkspaceView, {
            moduleAlias,
            moduleTitle: titleOf(record),
            moduleKind: 'dynamic',
          });
        },
      },
      {
        key: 'module-ui-orchestration-workspace',
        title: 'UI 编排',
        state: (record) => ({
          visible: moduleAliasOf(record) !== undefined && moduleKindOf(record) === 'dynamic',
        }),
        run({ record, openWorkspaceTab }) {
          const moduleAlias = moduleAliasOf(record);
          if (!moduleAlias || moduleKindOf(record) !== 'dynamic') return;
          openWorkspaceTab(uiOrchestrationWorkspace, {
            moduleAlias,
            moduleTitle: titleOf(record),
          });
        },
      },
      {
        key: 'module-openapi-page',
        title: '查看 OpenAPI',
        state: (record) => {
          const moduleAlias = moduleAliasOf(record);
          return { visible: moduleAlias !== undefined && openApiModuleAliases.value.has(moduleAlias) };
        },
        run({ record, openPage }) {
          const moduleAlias = moduleAliasOf(record);
          // The catalog only controls presentation. The OpenAPI endpoint itself
          // remains the authoritative authorization and describability check.
          if (!moduleAlias || !openApiModuleAliases.value.has(moduleAlias)) return;
          openPage(createModuleOpenApiPageDescriptor(moduleAlias, titleOf(record)));
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

/**
 * Action management has no menu identity of its own. It is the standard action
 * resource page entered with one governed module as its required navigator.
 */
function createModuleActionPageDescriptor(moduleAlias: string, moduleTitle?: string): PageDescriptor {
  return {
    pageType: 'dynamic-module',
    openMode: 'dynamic-runner',
    hostType: 'module-page-host',
    title: `动作：${moduleTitle ?? moduleAlias}`,
    target: { moduleAlias: 'platform.module_action', pageMode: 'LIST' },
    params: {
      [NAVIGATOR_ENTRY_MODULE_ALIAS_QUERY_KEY]: 'platform.module',
      [NAVIGATOR_ENTRY_RECORD_ID_QUERY_KEY]: moduleAlias,
    },
    tabPolicy: { identity: 'by-params', closable: true, cacheable: true },
  };
}

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
