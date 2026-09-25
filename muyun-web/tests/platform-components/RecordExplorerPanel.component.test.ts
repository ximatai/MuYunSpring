import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import RecordExplorerPanel from '@/platform-components/RecordExplorerPanel.vue';

describe('RecordExplorerPanel', () => {
  it.each(['header', 'toolbar'] as const)(
    'preserves subtitle and refresh state with custom titles in %s',
    async (utilityPlacement) => {
      const wrapper = mount(RecordExplorerPanel, {
        props: { title: '字段来源', subtitle: '选择编排来源', utilityPlacement, refreshDisabled: true },
        slots: { title: '<nav>已有字段 / 组件库</nav>' },
      });
      expect(wrapper.text()).toContain('选择编排来源');
      const refresh = () =>
        wrapper.findAll('button').filter((button) => button.attributes('title')?.startsWith('刷新'));
      expect(refresh()).toHaveLength(1);
      expect(refresh()[0].attributes('disabled')).toBeDefined();
      await refresh()[0].trigger('click');
      expect(wrapper.emitted('refresh')).toBeUndefined();
      await wrapper.setProps({ refreshDisabled: false });
      await refresh()[0].trigger('click');
      expect(wrapper.emitted('refresh')).toHaveLength(1);
      await wrapper.setProps({ refreshable: false });
      expect(refresh()).toHaveLength(0);
      wrapper.unmount();
    },
  );

  it('keeps search and refresh usable with a custom title and separate toolbar', async () => {
    const wrapper = mount(RecordExplorerPanel, {
      props: { title: '已有字段', utilityPlacement: 'toolbar' },
      slots: { title: '<span>已有字段 / 组件库</span>', 'utility-actions': '<span>系统字段</span>' },
    });
    expect(wrapper.find('header').text()).toContain('已有字段 / 组件库');
    const toolbar = wrapper.find('.record-explorer-toolbar');
    expect(toolbar.text()).toContain('系统字段');
    await toolbar.find('[title="搜索已有字段"]').trigger('click');
    expect(wrapper.find('input').exists()).toBe(true);
    await toolbar.find('[title="刷新已有字段"]').trigger('click');
    expect(wrapper.emitted('refresh')).toHaveLength(1);
    wrapper.unmount();
  });

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
