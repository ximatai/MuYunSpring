import { createLanguageContext } from '@/web-core/language';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref, nextTick } from 'vue';
import { describe, expect, it } from 'vitest';
import type { HttpClient, HttpRequestOptions } from '@/web-core/http';
import {
  createModuleContext,
  ModuleContextProvider,
  ModuleHttpProvider,
  useModuleContext,
  useModuleTreeContext,
  type ModuleContext,
} from '@/web-core/module/moduleContext';

function mountConsumer(consume: () => void) {
  const requests: HttpRequestOptions[] = [];
  const http: HttpClient = {
    async request(options) {
      requests.push(options);
      return (
        options.path.endsWith('/query')
          ? { list: [], total: 0 }
          : { moduleAlias: 'crm.customer', actions: [], capabilities: [], abilities: ['tree'] }
      ) as never;
    },
  };
  const Consumer = defineComponent({
    setup() {
      consume();
      return () => null;
    },
  });
  const wrapper = mount(ModuleHttpProvider, {
    props: { http },
    slots: {
      default: () =>
        h(
          ModuleContextProvider,
          { moduleAlias: 'crm.customer' },
          {
            default: () => h(Consumer),
          },
        ),
    },
  });
  return { wrapper, requests };
}

describe('module context hooks', () => {
  it('gives newly mounted consumers the current client while existing and disposed contexts retain their client', async () => {
    const requests: string[] = [];
    const client = (tenant: string): HttpClient => ({
      async request(options) {
        requests.push(`${tenant}:${options.path}`);
        return { moduleAlias: 'crm.customer', actions: [], capabilities: [] } as never;
      },
    });
    const first = client('first');
    const second = client('second');
    const http = ref(first);
    const generation = ref(0);
    const contexts: ModuleContext<unknown>[] = [];
    const Consumer = defineComponent({
      setup() {
        contexts.push(useModuleContext({ moduleAlias: 'crm.customer' }));
        return () => null;
      },
    });
    const Root = defineComponent({
      setup: () => () =>
        h(
          ModuleHttpProvider,
          { http: http.value },
          {
            default: () => h(Consumer, { key: generation.value }),
          },
        ),
    });
    const wrapper = mount(Root);
    http.value = second;
    await nextTick();
    await contexts[0]!.crud.query();
    generation.value += 1;
    await nextTick();
    await contexts[1]!.crud.query();
    await contexts[0]!.crud.query();
    expect(requests.filter((path) => path.endsWith('/query'))).toEqual([
      'first:/crm.customer/query',
      'second:/crm.customer/query',
      'first:/crm.customer/query',
    ]);
    wrapper.unmount();
  });

  it('reuses the injected default context but honors an explicit VIEW access', async () => {
    let completion!: Promise<unknown>;
    const { wrapper, requests } = mountConsumer(() => {
      const inherited = useModuleContext();
      expect(useModuleContext()).toBe(inherited);
      const view = useModuleContext({ runtimeAccess: 'VIEW' });
      expect(view).not.toBe(inherited);
      completion = view.runtime.load().then(() => view.crud.view('customer-1'));
    });
    await completion;
    expect(requests.map(({ path }) => path)).toEqual([
      '/platform.module/crm.customer/context',
      '/platform.module/crm.customer/view-context',
      '/crm.customer/view/customer-1',
    ]);
    wrapper.unmount();
  });

  it('honors VIEW access in the tree hook', async () => {
    let completion!: Promise<unknown>;
    const { wrapper, requests } = mountConsumer(() => {
      completion = useModuleTreeContext({ runtimeAccess: 'VIEW' }).runtime.load();
    });
    await completion;
    expect(requests.map(({ path }) => path)).toEqual(['/platform.module/crm.customer/view-context']);
    wrapper.unmount();
  });

  it('preserves the source navigator context for reference queries in both hooks', async () => {
    let completion!: Promise<unknown>;
    const { wrapper, requests } = mountConsumer(() => {
      const options = {
        runtimeAccess: 'REFERENCE' as const,
        navigatorReference: { hostModuleAlias: 'crm.order', targetLevelKey: 'customer' },
      };
      const context = useModuleContext(options);
      const treeContext = useModuleTreeContext(options);
      completion = Promise.all([context.crud.query({}), treeContext.tree.tree({})]);
    });
    await completion;
    expect(requests.filter(({ path }) => path.endsWith('/query')).map(({ path }) => path)).toEqual([
      '/crm.customer/navigator/reference/query',
      '/crm.customer/navigator/reference/tree/query',
    ]);
    expect(requests.some(({ path }) => path.endsWith('/reference-context'))).toBe(true);
    for (const request of requests.filter(({ path }) => path.endsWith('/query'))) {
      expect(request.body).toMatchObject({
        navigatorHostModuleAlias: 'crm.order',
        navigatorTargetLevelKey: 'customer',
      });
    }
    wrapper.unmount();
  });
});

it('honors an explicit language in an injected VIEW session without replacing its transport or execution facts', async () => {
  const parentLanguage = createLanguageContext();
  const childLanguage = createLanguageContext({ languageTag: 'en' });
  let requests = 0;
  const http: HttpClient = {
    async request() {
      requests++;
      return {
        moduleAlias: 'test.language',
        title: '原始模块名称',
        actions: [],
        capabilities: [],
        uiDescriptor: { moduleAlias: 'test.language', title: '原始标题' },
        textBindings: {
          '/title': {
            source: {
              sourceKind: 'CODE',
              sourceId: 'module/test.language/title',
              sourceVersion: 'v1',
              originalText: '原始模块名称',
            },
            languages: {
              en: {
                text: 'English module',
                requestedTag: 'en',
                resourceTag: 'en',
                missing: false,
                fallback: false,
              },
            },
          },
          '/uiDescriptor/title': {
            source: {
              sourceKind: 'CODE',
              sourceId: 'test.title',
              sourceVersion: 'v1',
              originalText: '原始标题',
            },
            languages: {
              en: {
                text: 'English title',
                requestedTag: 'en',
                resourceTag: 'en',
                missing: false,
                fallback: false,
              },
            },
          },
        },
      } as never;
    },
  };
  const parent = createModuleContext({
    moduleAlias: 'test.language',
    http,
    language: parentLanguage,
    runtimeAccess: 'VIEW',
  });
  let child!: ModuleContext<unknown>;
  let inherited!: ModuleContext<unknown>;
  const Consumer = defineComponent({
    setup() {
      inherited = useModuleContext();
      expect(useModuleContext({ language: parentLanguage })).toBe(inherited);
      child = useModuleContext({ language: childLanguage });
      return () => h('p', child.runtime.presentationSnapshot?.()?.uiDescriptor?.title);
    },
  });
  const wrapper = mount(ModuleContextProvider, {
    props: { context: parent },
    slots: { default: () => h(Consumer) },
  });
  try {
    await flushPromises();
    expect(wrapper.text()).toBe('English title');
    expect(child.runtime.presentationSnapshot?.()?.title).toBe('English module');
    expect(child.runtime.snapshot()?.title).toBe('原始模块名称');
    expect(child.http).toBe(inherited.http);
    expect(child.crud).toBe(inherited.crud);
    expect(child.runtime.ready).toBe(parent.runtime.ready);
    expect(child.runtime.snapshot()).toBe(parent.runtime.snapshot());
    expect(parent.runtime.presentationSnapshot?.()?.uiDescriptor?.title).toBe('原始标题');
    childLanguage.setLanguage('zh-CN');
    await nextTick();
    expect(wrapper.text()).toBe('原始标题');
    parentLanguage.setLanguage('en');
    await nextTick();
    expect(wrapper.text()).toBe('原始标题');
    expect(requests).toBe(1);
  } finally {
    wrapper.unmount();
  }
});
