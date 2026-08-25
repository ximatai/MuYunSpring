import { shallowMount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ManagementPanelHeader from '@/platform-components/ManagementPanelHeader.vue';

describe('ManagementPanelHeader', () => {
  it('uses a concise refresh tooltip without repeating the title', () => {
    const wrapper = shallowMount(ManagementPanelHeader, {
      props: {
        title: '菜单方案',
        titleActionIcon: 'reload',
        titleActionTitle: '刷新菜单方案',
      },
    });

    const action = wrapper.findComponent({ name: 'UiButton' });
    expect(action.props('title')).toBe('刷新：菜单方案');
    expect(action.props('ariaLabel')).toBe('刷新：菜单方案');
  });

  it('honors the caller-provided disabled state for a title refresh action', () => {
    const wrapper = shallowMount(ManagementPanelHeader, {
      props: { title: '用户管理', titleActionIcon: 'reload', titleActionDisabled: true },
    });

    expect(wrapper.findComponent({ name: 'UiButton' }).props('disabled')).toBe(true);
  });
});
