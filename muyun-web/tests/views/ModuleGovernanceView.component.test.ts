import { describe, expect, it } from 'vitest';
import { shallowMount } from '@vue/test-utils';
import { defineComponent } from 'vue';
import ModuleGovernanceView from '@/views/ModuleGovernanceView.vue';
import MetadataOrchestrationView from '@/views/MetadataOrchestrationView.vue';
import ModuleExperienceProfileOverview from '@/views/ModuleExperienceProfileOverview.vue';
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
