import { onMounted, onUnmounted, ref, toValue, watch, type MaybeRefOrGetter } from 'vue';

/** Hidden workspaces retain their last presentation until they have a real width. */
export function useWorkspaceDetailSurface(
  host: MaybeRefOrGetter<HTMLElement | undefined>,
  minimumWidth: MaybeRefOrGetter<number>,
) {
  const narrow = ref(false);
  let observer: ResizeObserver | undefined;
  function update() {
    const width = toValue(host)?.getBoundingClientRect().width;
    if (width != null && width > 0) narrow.value = width < toValue(minimumWidth);
  }
  onMounted(() => {
    if (typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(update);
      const element = toValue(host);
      if (element) observer.observe(element);
    } else if (typeof window !== 'undefined') {
      window.addEventListener('resize', update);
    }
    update();
  });
  watch(
    () => toValue(host),
    (element, previous) => {
      if (previous) observer?.unobserve(previous);
      if (element) observer?.observe(element);
      update();
    },
    { flush: 'post' },
  );
  watch(() => toValue(minimumWidth), update);
  onUnmounted(() => {
    observer?.disconnect();
    if (typeof window !== 'undefined') window.removeEventListener('resize', update);
  });
  return narrow;
}
