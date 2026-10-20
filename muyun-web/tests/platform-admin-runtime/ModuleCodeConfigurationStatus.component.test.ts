import { describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { reactive } from 'vue';
import type { ModulePageCardAssistantContext } from '@muyun/dynamic-page-runtime';
import ModuleCodeConfigurationStatus from '@/platform-admin-runtime/ModuleCodeConfigurationStatus.vue';
import { canConfigureModuleCodes, moduleCodeCatalog } from '@/platform-admin-runtime/moduleCodeConfiguration';
const { navigation } = vi.hoisted(() => ({ navigation: { openPage: vi.fn() } }));
vi.mock('@muyun/platform-workbench', async (original) => ({
  ...(await original<typeof import('@muyun/platform-workbench')>()),
  useWorkbenchNavigation: () => navigation,
}));
describe('module code configuration guidance', () => {
  it('loads the selected module fact, explains missing models and removes guidance when fields become available', async () => {
    const request = vi.fn().mockResolvedValue({ configurable: false, reason: 'no-model' });
    const module = reactive({ http: { request } }) as unknown as ModulePageCardAssistantContext['module'];
    const context: ModulePageCardAssistantContext = {
      module,
      mode: 'view',
      record: { id: 'demo', moduleKind: 'dynamic', title: '业务' },
      loadedRecords: [],
      formSessionKey: 1,
      saving: false,
      loading: false,
      loadFailed: false,
    };
    const wrapper = mount(ModuleCodeConfigurationStatus, { props: { context } });
    try {
      await flushPromises();
      expect(request).toHaveBeenCalledWith({ path: '/platform.module/demo/code-configuration' });
      expect(wrapper.text()).toContain('尚未关联业务数据模型');
      await wrapper.get('button').trigger('click');
      expect(navigation.openPage.mock.calls.at(-1)![0].target.query).toMatchObject({
        moduleAlias: 'demo',
        governanceTab: 'metadata',
      });
      request.mockResolvedValue({ configurable: true });
      await moduleCodeCatalog(module).refresh('demo');
      await flushPromises();
      expect(wrapper.find('[role="status"]').exists()).toBe(false);
      expect(canConfigureModuleCodes(module, 'demo')).toBe(true);
    } finally {
      wrapper.unmount();
    }
  });
  it('offers retry after a failed check without treating the module as unsupported', async () => {
    const request = vi
      .fn()
      .mockRejectedValueOnce(new Error('temporarily unavailable'))
      .mockResolvedValue({ configurable: false, reason: 'not-declared' });
    const context = {
      module: { http: { request } },
      mode: 'view',
      record: { id: 'static', moduleKind: 'static' },
      loadedRecords: [],
    } as unknown as ModulePageCardAssistantContext;
    const wrapper = mount(ModuleCodeConfigurationStatus, { props: { context } });
    try {
      await flushPromises();
      expect(wrapper.text()).toContain('暂时无法检查业务编码配置条件');
      await wrapper.get('button').trigger('click');
      await flushPromises();
      expect(wrapper.find('[role="status"]').exists()).toBe(false);
    } finally {
      wrapper.unmount();
    }
  });
});
