import { describe, expect, it } from 'vitest';
import { shallowMount } from '@vue/test-utils';
import ModuleGovernanceView from '@/views/ModuleGovernanceView.vue';
import MetadataOrchestrationView from '@/views/MetadataOrchestrationView.vue';
import PageCompositionWorkspace from '@/views/PageCompositionWorkspace.vue';
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

  it('keeps deferred tabs governed and mounts the new page composer rather than the legacy UI configuration view', async () => {
    const wrapper = shallowMount(ModuleGovernanceView, {
      props: { moduleAlias: 'education.exam', governanceTab: 'capabilities' },
    });

    expect(wrapper.findComponent(MetadataOrchestrationView).exists()).toBe(false);
    expect(wrapper.findComponent(RecordDetailPanel).props('title')).toBe('能力');

    await wrapper.setProps({ governanceTab: 'ui' });
    expect(wrapper.findComponent(PageCompositionWorkspace).props()).toMatchObject({
      moduleAlias: 'education.exam',
    });
    expect(wrapper.findComponent(RecordDetailPanel).exists()).toBe(false);
  });
});
