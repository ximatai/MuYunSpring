import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { describe, expect, it } from 'vitest';
import type { HttpClient, HttpRequestOptions } from '@/web-core/http';
import {
  ModuleContextProvider,
  ModuleHttpProvider,
  useModuleContext,
  useModuleTreeContext,
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
