import { h } from 'vue';
import { DOMWrapper, flushPromises, mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import UiDropdown from '@/vue-ui-antdv/components/UiDropdown.vue';

it('keeps optional icons decorative while preserving text, disabled items, danger styling and selection', async () => {
  const wrapper = mount(UiDropdown, {
    attachTo: document.body,
    props: {
      items: [
        { key: 'plain', title: '普通操作' },
        { key: 'skin', title: '皮肤切换', iconName: 'skin', disabled: true },
        { key: 'logout', title: '退出登录', iconName: 'power', danger: true },
      ],
    },
    slots: {
      default: ({ toggle, open }) =>
        h(
          'button',
          {
            'aria-expanded': open,
            onClick: (event: MouseEvent) => {
              event.stopPropagation();
              toggle();
            },
          },
          '打开菜单',
        ),
    },
  });
  try {
    await wrapper.get('button').trigger('click');
    await flushPromises();
    const items = Array.from(
      document.querySelectorAll<HTMLElement>('.muyun-ui-dropdown-overlay .ant-dropdown-menu-item'),
    );
    expect(items.map((item) => item.textContent)).toEqual(['普通操作', '皮肤切换', '退出登录']);
    expect(items[0]!.querySelector('.ui-dropdown-item-icon')).toBeNull();
    expect(items[1]!.querySelector('.ui-dropdown-item-icon')?.getAttribute('aria-hidden')).toBe('true');
    expect(items[1]!.querySelector('svg')).not.toBeNull();
    await new DOMWrapper(items[1]!).trigger('click');
    expect(wrapper.emitted('select')).toBeUndefined();
    expect(items[2]!.classList.contains('ant-dropdown-menu-item-danger')).toBe(true);
    await new DOMWrapper(items[2]!).trigger('click');
    expect(wrapper.emitted('select')).toEqual([['logout']]);
    expect(wrapper.get('button').attributes('aria-expanded')).toBe('false');
  } finally {
    wrapper.unmount();
  }
});
