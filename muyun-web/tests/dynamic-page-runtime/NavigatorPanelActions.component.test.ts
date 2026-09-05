import { shallowMount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import NavigatorPanelActions from '@/dynamic-page-runtime/NavigatorPanelActions.vue';
import type { ModuleContext } from '@muyun/web-core';

const context = {} as ModuleContext<unknown>;

describe('NavigatorPanelActions', () => {
  it('hides sorting when the runtime marks it invisible', () => {
    const wrapper = shallowMount(NavigatorPanelActions, {
      props: {
        context,
        title: '字典类目',
        sort: { visible: false, enabled: false },
        createAvailable: false,
      },
    });

    expect(wrapper.findComponent({ name: 'RecordPanelButton' }).exists()).toBe(false);
  });

  it('renders the runtime disabled reason and active state', () => {
    const wrapper = shallowMount(NavigatorPanelActions, {
      props: {
        context,
        title: '字典类目',
        sort: {
          visible: true,
          enabled: false,
          active: true,
          disabledReason: '清空搜索后可调整排序',
        },
        createAvailable: false,
      },
    });

    const button = wrapper.findComponent({ name: 'RecordPanelButton' });
    expect(button.props('selected')).toBe(true);
    expect(button.props('disabled')).toBe(true);
    expect(button.props('title')).toBe('清空搜索后可调整排序');
    expect(button.props('ariaLabel')).toBe('结束排序');
  });
});
