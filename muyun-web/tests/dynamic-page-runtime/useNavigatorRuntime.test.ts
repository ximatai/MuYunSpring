import { describe, expect, it } from 'vitest';
import { ref } from 'vue';
import { createModuleContext, createLanguageContext, type HttpClient } from '@/web-core';
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

it('uses the current language when a navigator target finishes loading after a switch', async () => {
  const language = createLanguageContext();
  let finishTarget!: (value: unknown) => void;
  let targetStarted!: () => void;
  const started = new Promise<void>((resolve) => {
    targetStarted = resolve;
  });
  const http = {
    request: async ({ path }: { path: string }) => {
      if (path.includes('demo.source')) {
        targetStarted();
        return new Promise<unknown>((resolve) => {
          finishTarget = resolve;
        });
      }
      return {
        moduleAlias: 'demo.host',
        capabilities: [],
        actions: [],
        uiDescriptor: {
          moduleAlias: 'demo.host',
          page: {
            detail: {},
            navigator: {
              levels: [{ key: 'source', sourceModuleAlias: 'demo.source', kind: 'LIST', title: '分类' }],
            },
          },
        },
        textBindings: {
          '/uiDescriptor/page/navigator/levels/0/title': {
            source: {
              sourceKind: 'CODE',
              sourceId: 'navigator.source.title',
              sourceVersion: 'v1',
              originalText: '分类',
            },
            languages: {
              en: {
                text: 'Categories',
                requestedTag: 'en',
                resourceTag: 'en',
                missing: false,
                fallback: false,
              },
            },
          },
        },
      };
    },
  } as unknown as HttpClient;
  const context = createModuleContext<QueryListRecord>({ http, language, moduleAlias: 'demo.host' });
  const runtime = useNavigatorRuntime(context);
  const loading = runtime.loadRuntimeForm(
    ref(true),
    () => false,
    () => {},
  );
  await started;
  language.setLanguage('en');
  finishTarget({ moduleAlias: 'demo.source', capabilities: [], actions: [] });
  await loading;
  expect(runtime.navigatorLevels.value[0].descriptor.title).toBe('Categories');
  const level = runtime.navigatorLevels.value[0];
  language.setLanguage('zh-CN');
  expect(runtime.navigatorLevels.value[0]).toBe(level);
  expect(level.descriptor.title).toBe('分类');
});
