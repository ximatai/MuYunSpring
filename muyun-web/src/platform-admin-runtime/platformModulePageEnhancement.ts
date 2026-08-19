import { ref } from 'vue';
import type { QueryListRecord } from '@muyun/platform-components';
import type { ModulePageEnhancement, ModulePageWorkspaceView } from '@muyun/dynamic-page-runtime';
import { createModuleOpenApiPageDescriptor, loadOpenApiCatalog } from './moduleOpenApi';
import { moduleActionManagementWorkspaceView } from '../views/moduleActionManagementWorkspaceView';
import { metadataOrchestrationWorkspaceView } from '../views/metadataOrchestrationWorkspaceView';

const openApiModuleAliases = ref<ReadonlySet<string>>(new Set());
let openApiCatalogRevision = 0;
// Workspace definitions use the platform-workbench's serializable input
// contract. The enhancement boundary exposes the equivalent dynamic-runtime
// contract, so adapt them once at this composition edge.
const moduleActionWorkspaceView = moduleActionManagementWorkspaceView as unknown as ModulePageWorkspaceView;
const metadataWorkspaceView = metadataOrchestrationWorkspaceView as unknown as ModulePageWorkspaceView;

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
  workspaceViews: [moduleActionWorkspaceView, metadataWorkspaceView],
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
