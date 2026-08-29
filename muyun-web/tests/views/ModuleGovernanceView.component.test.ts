import { describe, expect, it } from 'vitest';
import { shallowMount } from '@vue/test-utils';
import ModuleGovernanceView from '@/views/ModuleGovernanceView.vue';
import MetadataOrchestrationView from '@/views/MetadataOrchestrationView.vue';
import { RecordDetailPanel } from '@/platform-components';

describe('ModuleGovernanceView', () => {
  it('starts with the migrated metadata surface and keeps its module scope', () => {
    const wrapper = shallowMount(ModuleGovernanceView, {
      props: { moduleAlias: 'education.exam', moduleTitle: '考试管理' },
    });

    expect(wrapper.findComponent(MetadataOrchestrationView).props()).toMatchObject({
      moduleAlias: 'education.exam',
      moduleTitle: '考试管理',
    });
  });

  it('renders a governed placeholder rather than a second page implementation for deferred tabs', async () => {
    const wrapper = shallowMount(ModuleGovernanceView, {
      props: { moduleAlias: 'education.exam', governanceTab: 'capabilities' },
    });

    expect(wrapper.findComponent(MetadataOrchestrationView).exists()).toBe(false);
    expect(wrapper.findComponent(RecordDetailPanel).props('title')).toBe('能力');

    await wrapper.setProps({ governanceTab: 'ui' });
    expect(wrapper.findComponent(RecordDetailPanel).props('title')).toBe('页面配置');
  });
});
