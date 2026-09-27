import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, shallowMount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import ModuleRuntimeActivationStatus from '@/views/ModuleRuntimeActivationStatus.vue';
import ModuleGovernanceView from '@/views/ModuleGovernanceView.vue';
import MetadataOrchestrationView from '@/views/MetadataOrchestrationView.vue';
import ModuleExperienceProfileOverview from '@/views/ModuleExperienceProfileOverview.vue';
import BusinessRuleGovernanceSurface from '@/views/BusinessRuleGovernanceSurface.vue';
import ModuleBusinessPreview from '@/views/ModuleBusinessPreview.vue';
import PageCompositionWorkspace from '@/views/PageCompositionWorkspace.vue';
import { RecordDetailPanel } from '@/platform-components';
import type { ModuleGovernanceTab } from '@/views/moduleGovernanceWorkspaceView';

const KeepAlivePassthrough = defineComponent({ template: '<slot />' });
const mountGovernanceView = (props: {
  moduleAlias: string;
  moduleTitle?: string;
  governanceTab?: ModuleGovernanceTab;
}) =>
  shallowMount(ModuleGovernanceView, {
    props,
    global: { stubs: { KeepAlive: KeepAlivePassthrough } },
  });

describe('ModuleGovernanceView', () => {
  it('keeps one activation monitor mounted across every governance tab', async () => {
    const wrapper = mountGovernanceView({ moduleAlias: 'education.exam' });
    const status = () => wrapper.findComponent(ModuleRuntimeActivationStatus);
    expect(status().props('moduleAlias')).toBe('education.exam');
    for (const governanceTab of ['metadata', 'ui', 'rules'] as const) {
      await wrapper.setProps({ governanceTab });
      expect(status().props('moduleAlias')).toBe('education.exam');
      expect(wrapper.findAllComponents(ModuleRuntimeActivationStatus)).toHaveLength(1);
    }
    wrapper.unmount();
  });
  it('opens the operational business preview tab', () => {
    const wrapper = mountGovernanceView({ moduleAlias: 'education.exam', governanceTab: 'preview' });
    expect(wrapper.findComponent(ModuleBusinessPreview).props('moduleAlias')).toBe('education.exam');
    expect(wrapper.findComponent({ name: 'RecordRelationTabs' }).props('tabs')).toContainEqual({
      key: 'preview',
      title: '业务预览',
    });
  });

  it('opens business-rule governance in the same module-scoped workbench', () => {
    const wrapper = mountGovernanceView({ moduleAlias: 'education.exam', governanceTab: 'rules' });
    expect(wrapper.findComponent(BusinessRuleGovernanceSurface).props('moduleAlias')).toBe('education.exam');
    expect(wrapper.findComponent({ name: 'RecordRelationTabs' }).props('tabs')).toContainEqual({
      key: 'rules',
      title: '业务规则',
    });
  });
  it('starts with the existing overview surface and keeps its module scope', () => {
    const wrapper = mountGovernanceView({ moduleAlias: 'education.exam', moduleTitle: '考试管理' });

    expect(wrapper.findComponent(ModuleExperienceProfileOverview).props()).toMatchObject({
      moduleAlias: 'education.exam',
      moduleTitle: '考试管理',
    });
  });

  it('keeps the metadata and page-composer tabs governed', async () => {
    const wrapper = mountGovernanceView({ moduleAlias: 'education.exam', governanceTab: 'metadata' });

    expect(wrapper.findComponent(MetadataOrchestrationView).props()).toMatchObject({
      moduleAlias: 'education.exam',
    });
    expect(wrapper.findComponent(RecordDetailPanel).exists()).toBe(false);

    await wrapper.setProps({ governanceTab: 'ui' });
    expect(wrapper.findComponent(PageCompositionWorkspace).props()).toMatchObject({
      moduleAlias: 'education.exam',
    });
    expect(wrapper.findComponent(RecordDetailPanel).exists()).toBe(false);
  });
});

const { activationRequest } = vi.hoisted(() => ({ activationRequest: vi.fn() }));
vi.mock('@muyun/web-core', async (original) => ({
  ...(await original<typeof import('@muyun/web-core')>()),
  useModuleContext: () => ({ http: { request: activationRequest }, can: () => true }),
}));
vi.mock('@/platform-admin-runtime/pageRealtime', () => ({ usePageDataChangeHandler: vi.fn() }));

it.each(['FAILED', 'PENDING'] as const)(
  'updates the visible activation monitor to %s after a shared save without realtime',
  async (nextStatus) => {
    const { createMetadataWorkspace, provideMetadataWorkspace } = await import('@/views/metadataWorkspace');
    let status = 'ACTIVE';
    const rows = (records: unknown[]) => ({ records, pages: 1, totalKnown: true });
    activationRequest.mockImplementation(async ({ path }: { path: string }) => {
      if (path.endsWith('/runtime/activation'))
        return {
          moduleAlias: 'demo.order',
          status,
          desiredRevision: 2,
          installedRevision: status === 'ACTIVE' ? 2 : 1,
        };
      if (path.endsWith('/metadata-relations/query'))
        return rows([{ id: 'main', metadataId: 'meta', relationRole: 'main' }]);
      if (path.startsWith('/platform.metadata/view/'))
        return { id: 'meta', alias: 'order', title: '订单', version: 1 };
      if (path.endsWith('/fields/query')) return rows([]);
      if (path.endsWith('/field-properties')) return [];
      if (path.endsWith('/capabilities')) return { capabilities: [] };
      if (path.endsWith('/record-count')) return { recordCount: 0 };
      if (path === '/platform.field_spec/query')
        return rows([{ alias: 'string', title: '文本', enabled: true }]);
      if (path.endsWith('change-set-preview'))
        return {
          errors: [],
          warnings: [],
          fieldImpacts: [],
          schemaImpacts: [],
          orderImpacts: [],
          proposalFingerprint: 'checked',
        };
      if (path.endsWith('change-set-apply')) {
        status = nextStatus;
        return {};
      }
      throw new Error('Unexpected request ' + path);
    });
    const workspace = createMetadataWorkspace(
      { request: activationRequest },
      () => 'user',
      () => true,
    );
    const Host = defineComponent({
      setup() {
        provideMetadataWorkspace(workspace);
        return () => h(ModuleGovernanceView, { moduleAlias: 'demo.order' });
      },
    });
    const wrapper = mount(Host, {
      global: {
        stubs: {
          ModuleExperienceProfileOverview: true,
          RecordRelationTabs: true,
          UiActionButton: { template: '<button><slot /></button>' },
          UiPopover: { template: '<div><slot /><slot name="content" /></div>' },
        },
      },
    });
    try {
      await flushPromises();
      expect(wrapper.text()).not.toContain('生效失败');
      expect(wrapper.text()).not.toContain('正在应用修改');
      // The page subscribed before this shared session existed.
      const session = workspace.session('demo.order');
      workspace.focus(session);
      await session.ensureLoaded();
      session.adapter.prepareNewFieldDraft!({ title: '备注', fieldName: 'note', fieldSpecAlias: 'string' })();
      const confirmation = await session.adapter.prepareConfirmation!(new AbortController().signal);
      await confirmation.execute();
      await flushPromises();
      expect(wrapper.text()).toContain(nextStatus === 'FAILED' ? '生效失败' : '正在应用修改');
      expect(wrapper.text()).toContain('重试生效');
    } finally {
      wrapper.unmount();
      workspace.dispose();
      activationRequest.mockReset();
    }
  },
);
