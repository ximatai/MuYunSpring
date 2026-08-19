import { mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import UiTabs from '@/vue-ui-antdv/components/UiTabs.vue';

const tabs = [
  { key: 'application', title: '应用管理' },
  { key: 'metadata', title: '元数据管理' },
  { key: 'field-spec', title: '字段规格' },
];

function setTabBounds(wrapper: ReturnType<typeof mount>) {
  wrapper.findAll('.ui-tabs-label').forEach((label, index) => {
    const rect = { left: index * 100, right: (index + 1) * 100, top: 40, width: 100, height: 32 };
    Object.defineProperty(label.element, 'getBoundingClientRect', {
      configurable: true,
      value: () => rect,
    });
    Object.defineProperty(label.element.closest('.ant-tabs-tab')!, 'getBoundingClientRect', {
      configurable: true,
      value: () => rect,
    });
  });
}

async function dispatchPointer(
  element: EventTarget,
  type: string,
  options: { button?: number; pointerId: number; clientX: number; clientY?: number; pointerType?: string },
) {
  const event = new Event(type, { bubbles: true });
  Object.entries(options).forEach(([key, value]) => Object.defineProperty(event, key, { value }));
  element.dispatchEvent(event);
}

it('emits the reordered session tab keys after a pointer drag crosses a tab midpoint', async () => {
  const wrapper = mount(UiTabs, { props: { tabs, activeKey: 'application' } });
  setTabBounds(wrapper);
  const labels = wrapper.findAll('.ui-tabs-label');

  await dispatchPointer(labels[0].element, 'pointerdown', {
    button: 0,
    pointerId: 1,
    clientX: 20,
    clientY: 52,
  });
  await dispatchPointer(document, 'pointermove', { pointerId: 1, clientX: 170, clientY: 700 });

  expect(wrapper.find('.ui-tabs-drag-preview').text()).toBe('应用管理');
  expect(wrapper.find('.ui-tabs-drag-preview').attributes('style')).toContain('left: 150px');
  expect(wrapper.find('.ui-tabs-drag-preview').attributes('style')).toContain('top: 40px');
  expect(wrapper.find('.ui-tabs-label--dragging').exists()).toBe(true);
  await dispatchPointer(document, 'pointerup', { pointerId: 1, clientX: 170, clientY: 700 });

  expect(wrapper.emitted('reorder')).toEqual([[['metadata', 'application', 'field-spec']]]);
});

it('keeps touch gestures available for the native horizontal tab strip scroll', async () => {
  const wrapper = mount(UiTabs, { props: { tabs, activeKey: 'application' } });
  const label = wrapper.find('.ui-tabs-label');

  await dispatchPointer(label.element, 'pointerdown', {
    button: 0,
    pointerId: 1,
    clientX: 20,
    pointerType: 'touch',
  });
  await dispatchPointer(label.element, 'pointermove', { pointerId: 1, clientX: 170, pointerType: 'touch' });

  expect(wrapper.find('.ui-tabs-drag-preview').exists()).toBe(false);
  expect(wrapper.find('.ui-tabs-label--dragging').exists()).toBe(false);
});

it('opens the tab action menu on right click and emits the selected bulk close keys', async () => {
  const wrapper = mount(UiTabs, { props: { tabs, activeKey: 'application' } });
  const labels = wrapper.findAll('.ui-tabs-label');

  await labels[0].element
    .closest('.ant-tabs-tab')!
    .dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, clientX: 40, clientY: 60 }));

  const menu = wrapper.find('[role="menu"]');
  const menuItems = menu.findAll<HTMLButtonElement>('[role="menuitem"]');
  expect(menu.exists()).toBe(true);
  expect(menuItems.map((item) => item.text())).toEqual([
    '锁定标签',
    '关闭标签',
    '关闭左侧',
    '关闭右侧',
    '关闭其他',
    '关闭所有',
  ]);
  expect(menuItems[0].attributes('disabled')).toBeUndefined();
  expect(menuItems[2].attributes('disabled')).toBeDefined();

  await menuItems[3].trigger('click');
  expect(wrapper.emitted('closeTabs')).toEqual([[['metadata', 'field-spec']]]);
});

it('opens the tab action menu after a vertical touch swipe without taking over horizontal scrolling', async () => {
  const wrapper = mount(UiTabs, { props: { tabs, activeKey: 'application' } });
  const label = wrapper.find('.ui-tabs-label');

  await dispatchPointer(label.element, 'pointerdown', {
    button: 0,
    pointerId: 2,
    pointerType: 'touch',
    clientX: 40,
    clientY: 44,
  });
  await dispatchPointer(document, 'pointerup', {
    pointerId: 2,
    pointerType: 'touch',
    clientX: 44,
    clientY: 92,
  });

  expect(wrapper.find('[role="menu"]').exists()).toBe(true);
});

it('shows a pin button for locked tabs and keeps them out of bulk-close actions', async () => {
  const wrapper = mount(UiTabs, {
    props: { tabs: [{ ...tabs[0], pinned: true }, tabs[1], tabs[2]], activeKey: 'application' },
  });
  const labels = wrapper.findAll('.ui-tabs-label');

  const pinButton = wrapper.find('.ui-tabs-pin-button');
  expect(pinButton.attributes('aria-label')).toBe('取消锁定标签');
  expect(pinButton.attributes('title')).toBeUndefined();
  await pinButton.trigger('click');
  expect(wrapper.emitted('togglePin')).toEqual([['application']]);

  await labels[1].element
    .closest('.ant-tabs-tab')!
    .dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, clientX: 140, clientY: 60 }));
  const menuItems = wrapper.find('[role="menu"]').findAll<HTMLButtonElement>('[role="menuitem"]');
  expect(menuItems[0].text()).toBe('锁定标签');
  await menuItems[5].trigger('click');

  expect(wrapper.emitted('closeTabs')).toEqual([[['metadata', 'field-spec']]]);
});

it('does not reorder before the drag threshold and keeps the close control outside the drag handle', async () => {
  const wrapper = mount(UiTabs, { props: { tabs, activeKey: 'application' } });
  setTabBounds(wrapper);
  const labels = wrapper.findAll('.ui-tabs-label');

  await dispatchPointer(labels[0].element, 'pointerdown', { button: 0, pointerId: 1, clientX: 20 });
  await dispatchPointer(document, 'pointermove', { pointerId: 1, clientX: 23 });
  await dispatchPointer(document, 'pointerup', { pointerId: 1, clientX: 23 });

  expect(wrapper.find('.ant-tabs-tab-remove').element.closest('.ui-tabs-label')).toBeNull();
  expect(wrapper.emitted('reorder')).toBeUndefined();
});

it('cancels an in-progress drag when Escape is pressed', async () => {
  const wrapper = mount(UiTabs, { attachTo: document.body, props: { tabs, activeKey: 'application' } });
  setTabBounds(wrapper);
  const labels = wrapper.findAll('.ui-tabs-label');

  await dispatchPointer(labels[0].element, 'pointerdown', { button: 0, pointerId: 1, clientX: 20 });
  await dispatchPointer(document, 'pointermove', { pointerId: 1, clientX: 170 });
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  await wrapper.vm.$nextTick();

  expect(wrapper.find('.ui-tabs-label--dragging').exists()).toBe(false);
  expect(wrapper.emitted('reorder')).toBeUndefined();
  wrapper.unmount();
});

it('suppresses the drag completion click so an inactive dragged tab does not become active', async () => {
  vi.useFakeTimers();
  const wrapper = mount(UiTabs, { props: { tabs, activeKey: 'metadata' } });
  setTabBounds(wrapper);
  const labels = wrapper.findAll('.ui-tabs-label');

  await dispatchPointer(labels[0].element, 'pointerdown', { button: 0, pointerId: 1, clientX: 20 });
  await dispatchPointer(document, 'pointermove', { pointerId: 1, clientX: 170 });
  await dispatchPointer(document, 'pointerup', { pointerId: 1, clientX: 170 });
  await labels[0].trigger('click');
  vi.runAllTimers();

  expect(wrapper.emitted('update:activeKey')).toBeUndefined();
  vi.useRealTimers();
});
