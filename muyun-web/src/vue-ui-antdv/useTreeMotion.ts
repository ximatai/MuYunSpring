import { onBeforeUnmount, onBeforeUpdate, onMounted, onUpdated, computed, ref, type Ref } from 'vue';
import { treeChanges, treeSnapshot } from './treeStructure';
import type { UiTreeNode, UiTreeChangeReason } from './types';

export function useTreeMotion(
  root: Ref<HTMLElement | undefined>,
  options: {
    nodes: () => UiTreeNode[];
    flat: () => boolean;
    enabled: () => boolean;
    duration: () => number;
    reason: () => UiTreeChangeReason;
  },
) {
  const currentSnapshot = computed(() => treeSnapshot(options.nodes(), options.flat()));
  let snapshot = treeSnapshot([], options.flat());
  let layout = new Map<string, { rect: DOMRect; clone: HTMLElement }>();
  const animations = new Set<Animation>();
  const ghosts = new Set<HTMLElement>();
  let frame = 0;
  let mounted = false;
  let focused: { key: string; element: Element } | undefined;
  const reduced =
    typeof window !== 'undefined' ? window.matchMedia?.('(prefers-reduced-motion: reduce)') : undefined;
  const prefersReducedMotion = ref(reduced?.matches ?? false);
  function motionPreferenceChanged() {
    prefersReducedMotion.value = reduced?.matches ?? false;
    cancel();
  }
  function cancel() {
    cancelAnimationFrame(frame);
    animations.forEach((animation) => animation.cancel());
    animations.clear();
    ghosts.forEach((element) => element.remove());
    ghosts.clear();
  }
  onMounted(() => {
    mounted = true;
    snapshot = currentSnapshot.value;
    reduced?.addEventListener('change', motionPreferenceChanged);
  });
  onBeforeUpdate(() => {
    const active = root.value?.ownerDocument.activeElement;
    const key =
      active instanceof Element && root.value?.contains(active)
        ? active.closest<HTMLElement>('[data-ui-tree-key]')?.dataset.uiTreeKey
        : undefined;
    focused = key && active ? { key, element: active } : undefined;
    if (!options.enabled() || reduced?.matches || options.reason() !== 'interaction') {
      cancel();
      layout = new Map();
      return;
    }
    if (!treeChanges(snapshot, currentSnapshot.value).length) return;
    cancel();
    layout = new Map();
    root.value?.querySelectorAll<HTMLElement>('[data-ui-tree-key]').forEach((element) => {
      const key = element.dataset.uiTreeKey;
      if (key)
        layout.set(key, {
          rect: element.getBoundingClientRect(),
          clone: element.cloneNode(true) as HTMLElement,
        });
    });
  });
  function animate(element: HTMLElement, frames: Keyframe[], remove = false) {
    if (!element.animate) {
      if (remove) element.remove();
      return;
    }
    const animation = element.animate(frames, {
      duration: options.duration(),
      easing: 'cubic-bezier(0.2,0,0,1)',
    });
    animations.add(animation);
    const cleanup = () => {
      animations.delete(animation);
      if (remove) {
        element.remove();
        ghosts.delete(element);
      }
    };
    void animation.finished.then(cleanup, cleanup);
  }
  onUpdated(() => {
    const next = currentSnapshot.value;
    const changes = treeChanges(snapshot, next);
    snapshot = next;
    const container = root.value;
    if (
      focused &&
      container &&
      !focused.element.isConnected &&
      container.ownerDocument.activeElement === container.ownerDocument.body
    ) {
      [...container.querySelectorAll<HTMLElement>('[data-ui-tree-key]')]
        .find((element) => element.dataset.uiTreeKey === focused?.key)
        ?.focus({ preventScroll: true });
    }
    if (
      !changes.length ||
      !mounted ||
      !options.enabled() ||
      reduced?.matches ||
      options.reason() !== 'interaction'
    )
      return;
    const previous = layout;
    frame = requestAnimationFrame(() => {
      const container = root.value;
      if (!container) return;
      const elements = new Map(
        [...container.querySelectorAll<HTMLElement>('[data-ui-tree-key]')].map((element) => [
          element.dataset.uiTreeKey!,
          element,
        ]),
      );
      const host = container.getBoundingClientRect();
      changes.forEach(({ key, kind }) => {
        const element = elements.get(key);
        const old = previous.get(key);
        if ((kind === 'leave' || kind === 'reparent') && old) {
          const ghost = old.clone;
          [ghost, ...ghost.querySelectorAll<HTMLElement>('*')].forEach((child) => {
            child.removeAttribute('id');
            child.removeAttribute('data-ui-tree-key');
            child.removeAttribute('data-ui-drop-key');
            child.removeAttribute('tabindex');
          });
          ghost.setAttribute('aria-hidden', 'true');
          ghost.inert = true;
          Object.assign(ghost.style, {
            position: 'absolute',
            pointerEvents: 'none',
            margin: '0',
            top: `${old.rect.top - host.top + container.scrollTop}px`,
            left: `${old.rect.left - host.left + container.scrollLeft}px`,
            width: `${old.rect.width}px`,
            height: `${old.rect.height}px`,
          });
          container.append(ghost);
          ghosts.add(ghost);
          animate(ghost, [{ opacity: 0.7 }, { opacity: 0 }], true);
        }
        if (!element) return;
        if (kind === 'enter' || kind === 'reparent') animate(element, [{ opacity: 0 }, { opacity: 1 }]);
        else if (kind === 'update') animate(element, [{ opacity: 0.55 }, { opacity: 1 }]);
        else if (kind === 'move' && old) {
          const current = element.getBoundingClientRect();
          animate(element, [
            { transform: `translate(${old.rect.left - current.left}px,${old.rect.top - current.top}px)` },
            { transform: 'translate(0,0)' },
          ]);
        }
      });
    });
  });
  onBeforeUnmount(() => {
    cancel();
    reduced?.removeEventListener('change', motionPreferenceChanged);
  });
  return prefersReducedMotion;
}
