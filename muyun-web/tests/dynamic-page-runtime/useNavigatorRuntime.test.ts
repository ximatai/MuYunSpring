import { describe, expect, it } from 'vitest';
import { ref } from 'vue';
import { createModuleContext, type HttpClient } from '@/web-core';
import type { QueryListRecord } from '@/platform-components';
import { useNavigatorRuntime } from '@/dynamic-page-runtime/composables/useNavigatorRuntime';

describe('navigator standard sort availability', () => {
  it.each([
    { managed: true, tree: true, authorized: true, sortable: true },
    { managed: true, tree: true, authorized: false, sortable: false },
    { managed: false, tree: true, authorized: true, sortable: false },
    { managed: true, tree: false, authorized: true, sortable: false },
  ])('derives sorting from source abilities, permissions and management: %j', async (scenario) => {
    const http: HttpClient = {
      async request(request) {
        if (request.path === '/platform.module/demo.host/context') {
          return {
            moduleAlias: 'demo.host',
            actions: [],
            capabilities: [],
            uiDescriptor: {
              moduleAlias: 'demo.host',
              page: {
                template: 'TREE_MANAGEMENT',
                detail: {},
                navigator: {
                  contextBindings: [],
                  levels: [
                    {
                      key: 'source',
                      sourceModuleAlias: 'demo.source',
                      kind: 'TREE',
                      title: 'Source',
                      management: scenario.managed ? { editorSurface: 'default_form' } : undefined,
                    },
                  ],
                },
              },
            },
          } as never;
        }
        if (request.path === '/platform.module/demo.source/reference-context') {
          return {
            moduleAlias: 'demo.source',
            capabilities: scenario.tree ? ['TREE'] : [],
            actions: [{ actionCode: 'sort', authorized: scenario.authorized }],
          } as never;
        }
        throw new Error(`Unexpected request ${request.path}`);
      },
    };
    const context = createModuleContext<QueryListRecord>({ http, moduleAlias: 'demo.host' });
    const runtime = useNavigatorRuntime(context);
    await runtime.loadRuntimeForm(
      ref(true),
      () => false,
      (message) => {
        throw new Error(message);
      },
    );
    expect(runtime.navigatorLevels.value[0].sort).toEqual({
      visible: scenario.sortable,
      enabled: scenario.sortable,
    });
  });
});
