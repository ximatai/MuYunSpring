import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import UiRecordExplorerItem from '@/vue-ui-antdv/components/UiRecordExplorerItem.vue';

describe('UiRecordExplorerItem', () => {
  it('renders identity details and visual state classes', () => {
    const wrapper = mount(UiRecordExplorerItem, {
      props: {
        title: '研发中心',
        secondary: 'R&D',
        tag: '已启用',
        selected: true,
        muted: true,
      },
    });

    expect(wrapper.text()).toContain('研发中心');
    expect(wrapper.text()).toContain('R&D');
    expect(wrapper.text()).toContain('已启用');
    expect(wrapper.classes()).toContain('ui-record-explorer-item-selected');
    expect(wrapper.classes()).toContain('ui-record-explorer-item-muted');
  });

  it('emits click and enabled actions', async () => {
    const action = { key: 'edit', title: '编辑', showLabel: true };
    const wrapper = mount(UiRecordExplorerItem, {
      props: { title: '研发中心', clickable: true, actions: [action] },
    });

    await wrapper.trigger('click');
    await wrapper.get('.ui-record-explorer-item-action').trigger('click');

    expect(wrapper.emitted('click')).toHaveLength(1);
    expect(wrapper.emitted('action')).toEqual([[action]]);
  });

  it('does not emit disabled actions', async () => {
    const wrapper = mount(UiRecordExplorerItem, {
      props: { title: '研发中心', actions: [{ key: 'delete', title: '删除', disabled: true }] },
    });

    await wrapper.get('.ui-record-explorer-item-action').trigger('click');

    expect(wrapper.emitted('action')).toBeUndefined();
  });

  it('exposes the reason for a disabled inline action', () => {
    const wrapper = mount(UiRecordExplorerItem, {
      props: {
        title: '研发中心',
        actions: [
          { key: 'restore', title: '恢复', disabled: true, disabledReason: '无法恢复：生命周期已变化' },
        ],
      },
    });

    expect(wrapper.get('.ui-record-explorer-item-action').attributes('title')).toBe(
      '无法恢复：生命周期已变化',
    );
  });
});
