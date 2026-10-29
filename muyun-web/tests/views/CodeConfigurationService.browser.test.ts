import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import {
  createHttpClient,
  createModuleContext,
  createAssistantSurfaceRegistry,
  provideAssistantSurfaceHost,
  ModuleContextProvider,
  type HttpClient,
  type HttpRequestOptions,
} from '@muyun/web-core';
import { UiThemeProvider, UiSidePanelHost } from '@muyun/vue-ui-antdv';
import type { CurrentUser } from '@muyun/web-contracts';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext';
import { providePageLayout } from '@/platform-components/pageLayoutContext';
import { createCodeConfigurationClient } from '@/views/codeConfiguration';
import CodeConfigurationView from '@/views/CodeConfigurationView.vue';
import '@/styles.css';
import '@muyun/vue-ui-antdv/styles.css';
declare const __PLATFORM_SERVICE_ENABLED__: boolean;
declare const __PLATFORM_SERVICE_TOKEN__: string;

it.skipIf(!__PLATFORM_SERVICE_ENABLED__)(
  'shares an authorized code draft, previews without issuing and recovers a committed new-rule request',
  async () => {
    await page.viewport(1280, 900);
    const transport = createHttpClient({ baseUrl: '/api', token: __PLATFORM_SERVICE_TOKEN__ });
    const user = await transport.request<CurrentUser>({ path: '/iam.auth/context' });
    let writes = 0;
    let lost = true;
    const http: HttpClient = {
      async request<T>(options: HttpRequestOptions) {
        const result = await transport.request<T>(options);
        if (options.method === 'POST' && /\/save-requests\/[^/?]+$/.test(options.path)) {
          writes++;
          if (lost) {
            lost = false;
            throw new Error('lost committed response');
          }
        }
        return result;
      },
    };
    const context = createModuleContext({ http, moduleAlias: 'platform.code_rule' });
    await context.runtime.ready;
    const registry = createAssistantSurfaceRegistry();
    registry.activate('code-service');
    const Harness = defineComponent({
      setup() {
        provideCurrentUserContext(ref(user));
        providePageLayout('workspace');
        provideAssistantSurfaceHost({ registry, activePageInstanceKey: () => 'code-service' });
        return () =>
          h(UiThemeProvider, {}, () =>
            h(UiSidePanelHost, {}, () =>
              h(ModuleContextProvider, { context }, () =>
                h(CodeConfigurationView, { moduleAlias: 'education.receipt' }),
              ),
            ),
          );
      },
    });
    const render = () =>
      mount(Harness, {
        attachTo: document.body,
        global: { stubs: { TenantScopeExplorer: true, ModuleRuntimeActivationStatus: true } },
      });
    let wrapper = render();
    (wrapper.element as HTMLElement).style.cssText = 'display:block;height:800px';
    const tenant = async () => {
      wrapper.findComponent({ name: 'TenantScopeExplorer' }).vm.$emit('select', { id: 'demo' });
      await flushPromises();
      await expect.poll(() => wrapper.find('.code-config__rules-table').exists()).toBe(true);
    };
    try {
      await tenant();
      await page.getByRole('button', { name: '新增规则', exact: true }).click();
      await expect
        .poll(() => registry.snapshot()?.capabilities.some((item) => item.code === 'code-rule.revise-draft'))
        .toBe(true);
      const invoke = (code: string, input: unknown = {}) =>
        registry.invoke({ id: 'call', code, input }, registry.snapshot()!.token);
      await invoke('code-rule.revise-draft', { title: `协作编号 ${Date.now()}` });
      expect(writes).toBe(0);
      await invoke('code-rule.preview-draft');
      expect(writes).toBe(0);
      await page.getByRole('button', { name: '保存规则', exact: true }).click();
      await expect.poll(() => wrapper.find('.code-config__recovery').exists()).toBe(true);
      expect(writes).toBe(1);
      wrapper.unmount();
      wrapper = render();
      await tenant();
      await page.getByRole('button', { name: '核实原保存结果', exact: true }).click();
      await expect.poll(() => wrapper.text()).toContain('已确认原请求提交成功');
      await page.getByRole('button', { name: '采用当前规则并放弃本地候选', exact: true }).click();
      await expect.poll(() => wrapper.find('.code-config__recovery').exists()).toBe(false);
      expect(writes).toBe(1);
      const businessHttp: HttpClient = {
        request: (options) =>
          transport.request({ ...options, headers: { ...options.headers, 'X-MuYun-Tenant-Id': 'demo' } }),
      };
      const client = createCodeConfigurationClient(businessHttp);
      const targets = await client.targets('education.receipt');
      const rules = await client.rules(targets[0]!);
      expect(rules.some((rule) => rule.title.startsWith('协作编号'))).toBe(true);
    } finally {
      wrapper.unmount();
      await flushPromises();
    }
  },
  30000,
);
