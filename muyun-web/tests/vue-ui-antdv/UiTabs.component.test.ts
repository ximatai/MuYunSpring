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
