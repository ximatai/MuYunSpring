import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import { UiModal, UiSidePanel, useUiBlockingOverlayState } from '@/vue-ui-antdv';
import { createConfirmAction } from '@/vue-ui-antdv/confirm';
import BusinessNotificationPanel from '@/platform-components/BusinessNotificationPanel.vue';
import { Modal } from 'ant-design-vue';
const drawer = defineComponent({
  name: 'ADrawer',
  props: ['open'],
  setup(_, { slots }) {
    return () => h('div', slots.default?.());
  },
});
const modal = defineComponent({
  name: 'AModal',
  props: ['open'],
  setup(_, { slots }) {
    return () => h('div', slots.default?.());
  },
});
afterEach(() => vi.restoreAllMocks());
it('tracks visible drawers through hidden hosts, closing transitions and unmount without scanning UI library DOM', async () => {
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList);
  const open = ref(true),
    hidden = ref(false);
  const Harness = defineComponent({
    setup: () => () =>
      h('div', { style: hidden.value ? 'display:none' : '' }, [
        h(UiSidePanel, { open: open.value, renderMode: 'inline' }, () => '办理'),
      ]),
  });
  const state = useUiBlockingOverlayState();
  const wrapper = mount(Harness, { attachTo: document.body, global: { stubs: { ADrawer: drawer } } });
  await nextTick();
  expect(state.value).toBe(true);
  hidden.value = true;
  await nextTick();
  await nextTick();
  expect(state.value).toBe(false);
  hidden.value = false;
  await nextTick();
  await nextTick();
  expect(state.value).toBe(true);
  open.value = false;
  await nextTick();
  expect(state.value).toBe(true);
  wrapper.findComponent(drawer).vm.$emit('after-open-change', false);
  await nextTick();
  expect(state.value).toBe(false);
  open.value = true;
  await nextTick();
  expect(state.value).toBe(true);
  wrapper.unmount();
  expect(state.value).toBe(false);
});
it('defers notification presentation for a modal and restores the retained queue after its closing lifecycle', async () => {
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockReturnValue([{}] as unknown as DOMRectList);
  const open = ref(false);
  const Harness = defineComponent({
    setup: () => () =>
      h('div', [
        h(UiModal, { open: open.value, title: '确认' }, () => '办理确认'),
        h(BusinessNotificationPanel, {
          notifications: [
            { id: 'n', code: 'todo', title: '待办', content: '', dismissible: true, actions: [] },
          ],
          executeAction: () => {},
        }),
      ]),
  });
  const wrapper = mount(Harness, { attachTo: document.body, global: { stubs: { AModal: modal } } });
  expect(wrapper.get('.business-notification-panel').attributes('hidden')).toBeUndefined();
  open.value = true;
  await nextTick();
  await nextTick();
  expect(wrapper.get('.business-notification-panel').attributes('hidden')).toBeDefined();
  open.value = false;
  await nextTick();
  expect(wrapper.get('.business-notification-panel').attributes('hidden')).toBeDefined();
  wrapper.findComponent(modal).vm.$emit('after-close');
  await nextTick();
  expect(wrapper.get('.business-notification-panel').attributes('hidden')).toBeUndefined();
  expect(wrapper.get('.business-notification-card').text()).toContain('待办');
});
it('owns imperative confirmations until their closing lifecycle and releases failed creations', async () => {
  let options!: Parameters<typeof Modal.confirm>[0];
  const state = useUiBlockingOverlayState();
  const confirm = createConfirmAction((value) => {
    options = value;
    return { destroy() {}, update() {} };
  });
  const pending = confirm({ title: '确认' });
  expect(state.value).toBe(true);
  options.onCancel?.();
  expect(await pending).toBe(false);
  expect(state.value).toBe(true);
  options.afterClose?.();
  expect(state.value).toBe(false);
  const failed = createConfirmAction(() => {
    throw new Error('creation failed');
  });
  await expect(failed({ title: '确认' })).rejects.toThrow('creation failed');
  expect(state.value).toBe(false);
});
