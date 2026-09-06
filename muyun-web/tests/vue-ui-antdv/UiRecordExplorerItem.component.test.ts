import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import UiRecordExplorerItem from '@/vue-ui-antdv/components/UiRecordExplorerItem.vue';
import UiDropdown from '@/vue-ui-antdv/components/UiDropdown.vue';

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

  it('opens one menu and emits the selected child action without selecting the record', async () => {
    const list = { key: 'add-list', title: '添加到列表' };
    const form = { key: 'add-form', title: '添加到表单', disabled: true };
    const wrapper = mount(UiRecordExplorerItem, {
      attachTo: document.body,
      props: {
        title: '科目',
        actions: [{ key: 'add', title: '添加到…', items: [list, form] }],
      },
    });

    await wrapper.get('[aria-haspopup="menu"]').trigger('click');
    await vi.waitFor(() => expect(document.querySelectorAll('[role="menuitem"]')).toHaveLength(2));
    const menuItems = Array.from(document.querySelectorAll<HTMLElement>('[role="menuitem"]'));
    expect(menuItems[1].getAttribute('aria-disabled')).toBe('true');
    menuItems[1].click();
    expect(wrapper.emitted('action')).toBeUndefined();
    menuItems[0].click();

    await vi.waitFor(() => expect(wrapper.emitted('action')).toEqual([[list]]));
    expect(wrapper.emitted('click')).toBeUndefined();
  });

  it('rejects unavailable and unknown menu selections including disabled parent actions', async () => {
    const list = { key: 'add-list', title: '添加到列表' };
    const form = { key: 'add-form', title: '添加到表单', disabled: true };
    const action = { key: 'add', title: '添加到…', items: [list, form] };
    const wrapper = mount(UiRecordExplorerItem, { props: { title: '科目', actions: [action] } });
    const dropdown = wrapper.getComponent(UiDropdown);

    dropdown.vm.$emit('select', form.key);
    dropdown.vm.$emit('select', 'unknown');
    expect(wrapper.emitted('action')).toBeUndefined();

    await wrapper.setProps({ actions: [{ ...action, disabled: true }] });
    expect(wrapper.findComponent(UiDropdown).exists()).toBe(false);
    expect(wrapper.get('button').attributes('disabled')).toBeDefined();
    await wrapper.get('button').trigger('click');
    expect(wrapper.emitted('action')).toBeUndefined();
    expect(wrapper.emitted('click')).toBeUndefined();
  });

  it('keeps node selection, drag and double-click handlers isolated from inline and menu buttons', async () => {
    const click = vi.fn();
    const mousedown = vi.fn();
    const doubleClick = vi.fn();
    const keydown = vi.fn();
    const wrapper = mount(
      defineComponent({
        setup: () => () =>
          h('div', { onClick: click, onMousedown: mousedown, onDblclick: doubleClick, onKeydown: keydown }, [
            h(UiRecordExplorerItem, {
              title: '科目',
              actions: [
                { key: 'remove', title: '移除' },
                { key: 'add', title: '添加到…', items: [{ key: 'add-list', title: '添加到列表' }] },
              ],
            }),
          ]),
      }),
    );
    for (const button of wrapper.findAll('button')) {
      await button.trigger('mousedown');
      await button.trigger('click');
      await button.trigger('dblclick');
      await button.trigger('keydown', { key: 'Enter' });
    }

    expect(click).not.toHaveBeenCalled();
    expect(mousedown).not.toHaveBeenCalled();
    expect(doubleClick).not.toHaveBeenCalled();
    expect(keydown).not.toHaveBeenCalled();
    expect(wrapper.getComponent(UiRecordExplorerItem).emitted('click')).toBeUndefined();
  });
});
