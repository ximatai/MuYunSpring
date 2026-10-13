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

it.each([
  ['first', 'second'],
  ['second', 'first'],
])('projects every navigator after all targets settle (%s before %s)', async (early, late) => {
  const language = createLanguageContext();
  const finish = new Map<string, (value: unknown) => void>();
  let started!: () => void;
  const targetsStarted = new Promise<void>((resolve) => {
    started = resolve;
  });
  const slots = [
    { key: 'first', title: '一级', english: 'First' },
    { key: 'second', title: '二级', english: 'Second' },
  ];
  const http = {
    request: async ({ path }: { path: string }) => {
      const target = slots.find((slot) => path.includes(`demo.${slot.key}`));
      if (target)
        return new Promise<unknown>((resolve) => {
          finish.set(target.key, resolve);
          if (finish.size === 2) started();
        });
      return {
        moduleAlias: 'demo.host',
        capabilities: [],
        actions: [],
        uiDescriptor: {
          moduleAlias: 'demo.host',
          page: {
            detail: {},
            navigator: {
              levels: slots.map((slot) => ({
                key: slot.key,
                sourceModuleAlias: `demo.${slot.key}`,
                kind: 'LIST',
                title: slot.title,
              })),
            },
          },
        },
        textBindings: Object.fromEntries(
          slots.map((slot, index) => [
            `/uiDescriptor/page/navigator/levels/${index}/title`,
            {
              source: {
                sourceKind: 'CODE',
                sourceId: `navigator.${slot.key}.title`,
                sourceVersion: 'v1',
                originalText: slot.title,
              },
              languages: {
                en: {
                  text: slot.english,
                  requestedTag: 'en',
                  resourceTag: 'en',
                  missing: false,
                  fallback: false,
                },
              },
            },
          ]),
        ),
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
  await targetsStarted;
  finish.get(early)!({ moduleAlias: `demo.${early}`, capabilities: [], actions: [] });
  await new Promise<void>((resolve) => setImmediate(resolve));
  language.setLanguage('en');
  finish.get(late)!({ moduleAlias: `demo.${late}`, capabilities: [], actions: [] });
  await loading;
  expect(runtime.navigatorLevels.value.map((level) => level.descriptor.title)).toEqual(['First', 'Second']);
  const level = runtime.navigatorLevels.value[0];
  language.setLanguage('zh-CN');
  expect(runtime.navigatorLevels.value[0]).toBe(level);
  expect(level.descriptor.title).toBe('一级');
});
