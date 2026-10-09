import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch, type Ref } from 'vue';

const visibleOverlays = shallowRef(new Map<symbol, boolean>());

/** Reminders defer to visible interaction surfaces; the adapter owns their lifecycle. */
export function useUiBlockingOverlayState() {
  return computed(() => [...visibleOverlays.value.values()].some(Boolean));
}

export function registerUiBlockingOverlay() {
  const key = Symbol('ui-blocking-overlay');
  return {
    setVisible(visible: boolean) {
      const next = new Map(visibleOverlays.value);
      if (next.get(key) === visible) return;
      next.set(key, visible);
      visibleOverlays.value = next;
    },
    release() {
      const next = new Map(visibleOverlays.value);
      if (next.delete(key)) visibleOverlays.value = next;
    },
  };
}

/** Observe only this surface and its ancestors, including retained hidden tab hosts. */
export function useUiBlockingOverlayVisibility(open: () => boolean, content: Ref<HTMLElement | undefined>) {
  const registration = registerUiBlockingOverlay();
  const active = ref(open());
  let mutationObserver: MutationObserver | undefined;
  let resizeObserver: ResizeObserver | undefined;
  let mounted = false;
  function sync() {
    const element = content.value;
    registration.setVisible(
      Boolean(
        active.value &&
        element &&
        (typeof element.checkVisibility === 'function'
          ? element.checkVisibility({ checkVisibilityCSS: true })
          : element.getClientRects().length > 0 && !hiddenByAncestor(element)),
      ),
    );
  }
  function observe() {
    mutationObserver?.disconnect();
    resizeObserver?.disconnect();
    if (!mounted) return;
    sync();
    const element = content.value;
    if (!active.value || !element) return;
    mutationObserver = new MutationObserver(sync);
    for (let ancestor: HTMLElement | null = element; ancestor; ancestor = ancestor.parentElement)
      mutationObserver.observe(ancestor, { attributes: true, attributeFilter: ['class', 'style', 'hidden'] });
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(sync);
      resizeObserver.observe(element);
    }
  }
  watch(open, (value) => {
    // Keep ownership while the closing transition is still visible.
    if (value) active.value = true;
  });
  watch([active, content], observe, { flush: 'post' });
  onMounted(() => {
    mounted = true;
    observe();
  });
  onBeforeUnmount(() => {
    mutationObserver?.disconnect();
    resizeObserver?.disconnect();
    registration.release();
  });
  return (visible: boolean) => {
    active.value = visible || open();
    observe();
  };
}
function hiddenByAncestor(element: HTMLElement): boolean {
  for (let ancestor: HTMLElement | null = element; ancestor; ancestor = ancestor.parentElement) {
    const style = getComputedStyle(ancestor);
    if (style.display === 'none' || ['hidden', 'collapse'].includes(style.visibility)) return true;
  }
  return false;
}
