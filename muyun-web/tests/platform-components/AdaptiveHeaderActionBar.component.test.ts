import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import AdaptiveHeaderActionBar from '@/platform-components/AdaptiveHeaderActionBar.vue';

describe('AdaptiveHeaderActionBar', () => {
  afterEach(() => vi.restoreAllMocks());

  it('identifies the disabled operation so its explanation cannot be mistaken for a save failure', async () => {
    const wrapper = mount(AdaptiveHeaderActionBar, {
      props: {
        actions: [
          { key: 'edit', title: '编辑' },
          { key: 'delete', title: '删除', disabled: true, disabledReason: '没有此操作的权限' },
        ],
      },
    });
    await flushPromises();

    const tooltips = wrapper.findAllComponents({ name: 'UiTooltip' });
    expect(tooltips.map((tooltip) => tooltip.props('title'))).toEqual(['', '删除：没有此操作的权限']);
    const deleteButton = tooltips[1]!.findComponent({ name: 'UiActionButton' });
    expect(deleteButton.props('disabled')).toBe(true);
    deleteButton.vm.$emit('click', new MouseEvent('click'));
    expect(wrapper.emitted('action')).toBeUndefined();
    wrapper.unmount();
  });

  it('configures 更多 as a platform hover dropdown', async () => {
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(60);
    const wrapper = mount(AdaptiveHeaderActionBar, {
      attachTo: document.body,
      props: {
        actions: [
          { key: 'create', title: '新建', level: 'primary' },
          { key: 'edit', title: '编辑', level: 'standard' },
        ],
      },
    });

    await flushPromises();
    await flushPromises();

    expect(wrapper.findComponent({ name: 'UiDropdown' }).props('trigger')).toBe('hover');
  });
});
