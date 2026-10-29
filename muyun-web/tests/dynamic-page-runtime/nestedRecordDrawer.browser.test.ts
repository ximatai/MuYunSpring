import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import RecordModeDrawer from '@/platform-components/RecordModeDrawer.vue';
import RecordDetailDrawer from '@/platform-components/RecordDetailDrawer.vue';

it('pushes the owning record drawer and restores it when an action drawer closes', async () => {
  const childOpen = ref(false);
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h(
          RecordModeDrawer,
          { open: true, title: 'Device', mode: 'view', renderMode: 'inline', width: 'standard' },
          {
            default: () =>
              h(RecordDetailDrawer, {
                open: childOpen.value,
                title: 'Upgrade',
                renderMode: 'portal',
                scope: 'viewport',
                width: 'standard',
              }),
          },
        ),
    }),
    { attachTo: document.body },
  );
  try {
    await nextTick();
    const parent = wrapper.findAll('.ant-drawer-content-wrapper')[0]!;
    childOpen.value = true;
    await nextTick();
    await expect.poll(() => (parent.element as HTMLElement).style.transform).toContain('translateX');
    childOpen.value = false;
    await nextTick();
    await expect.poll(() => (parent.element as HTMLElement).style.transform).toBe('');
  } finally {
    wrapper.unmount();
  }
});
