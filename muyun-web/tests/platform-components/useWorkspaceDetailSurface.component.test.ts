import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import { afterEach, expect, it, vi } from 'vitest';
import { useWorkspaceDetailSurface } from '@/platform-components/useWorkspaceDetailSurface';

afterEach(() => vi.unstubAllGlobals());

it('uses real workspace width, retains the last mode while hidden, and disposes observation', async () => {
  let resize!: () => void;
  const disconnect = vi.fn();
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(callback: () => void) {
        resize = callback;
      }
      observe() {}
      unobserve() {}
      disconnect = disconnect;
    },
  );
  let width = 1152;
  const minimum = ref(1152);
  const element = document.createElement('section');
  element.getBoundingClientRect = () => ({ width }) as DOMRect;
  const Host = defineComponent({
    setup() {
      const narrow = useWorkspaceDetailSurface(ref(element), minimum);
      return () => h('span', narrow.value ? 'drawer' : 'docked');
    },
  });
  const wrapper = mount(Host);
  try {
    await nextTick();
    expect(wrapper.text()).toBe('docked');
    width = 1151;
    resize();
    await nextTick();
    expect(wrapper.text()).toBe('drawer');
    width = 0;
    resize();
    await nextTick();
    expect(wrapper.text()).toBe('drawer');
    width = 1280;
    resize();
    await nextTick();
    expect(wrapper.text()).toBe('docked');
    minimum.value = 1300;
    await nextTick();
    expect(wrapper.text()).toBe('drawer');
  } finally {
    wrapper.unmount();
  }
  expect(disconnect).toHaveBeenCalledOnce();
});

it('handles a workspace mounted after loading when ResizeObserver is unavailable', async () => {
  vi.stubGlobal('ResizeObserver', undefined);
  const host = ref<HTMLElement>();
  let width = 900;
  const element = document.createElement('section');
  element.getBoundingClientRect = () => ({ width }) as DOMRect;
  const wrapper = mount(
    defineComponent({
      setup() {
        const narrow = useWorkspaceDetailSurface(host, 1152);
        return () => h('span', narrow.value ? 'drawer' : 'docked');
      },
    }),
  );
  try {
    host.value = element;
    await nextTick();
    expect(wrapper.text()).toBe('drawer');
    width = 1280;
    window.dispatchEvent(new Event('resize'));
    await nextTick();
    expect(wrapper.text()).toBe('docked');
  } finally {
    wrapper.unmount();
  }
});
