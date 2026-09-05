import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import RecordExplorerPanel from '@/platform-components/RecordExplorerPanel.vue';

describe('RecordExplorerPanel', () => {
  it('uses the lightweight selected tool-button state while search is expanded', async () => {
    const wrapper = mount(RecordExplorerPanel, { props: { title: '应用列表' } });
    const search = wrapper
      .findAllComponents({ name: 'UiButton' })
      .find((button) => button.props('title') === '搜索应用列表');

    expect(search?.props('type')).toBe('text');
    await search?.trigger('click');

    expect(search?.props('type')).toBe('text');
    expect(search?.props('selected')).toBe(true);
    expect(wrapper.find('.record-explorer-search').exists()).toBe(true);
  });
});
