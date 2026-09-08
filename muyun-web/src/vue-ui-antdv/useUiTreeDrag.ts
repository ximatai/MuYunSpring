import { computed, onBeforeUnmount, ref, shallowRef, watch, type Ref } from 'vue';
import type { UiDragSource, UiDropPosition, UiDropTarget, UiTreeDropEvent, UiDropOperation } from './types';

interface Surface {
  root: HTMLElement;
  resolve: (
    origin: Element,
    y: number,
    position?: UiDropPosition,
    source?: UiDragSource,
    x?: number,
  ) => UiDropTarget | undefined;
  allow: (event: UiTreeDropEvent) => boolean;
  operation?: (source: UiDragSource) => UiDropOperation;
  drop: (event: UiTreeDropEvent) => void;
  feedback: (target?: UiDropTarget, rejected?: boolean) => void;
  /**
   * A canvas-like editor can opt into an intentional drag boundary: after the pointer has entered
   * this surface, leaving it abandons the transient placement instead of carrying a stale drop
   * candidate across unrelated controls.
   */
  cancelWhenPointerLeaves?: boolean;
}
interface Session {
  owner: string;
  source: () => UiDragSource | undefined;
  started: boolean;
  keyboard: boolean;
  x: number;
  y: number;
  startX: number;
  startY: number;
  position?: UiDropPosition;
  start?: (source: UiDragSource) => void;
  end?: (cancelled: boolean) => void;
}
let instanceSequence = 0;
export const createUiTreeInstanceId = () => `ui-tree-${++instanceSequence}`;
const hubs = new WeakMap<Document, ReturnType<typeof createHub>>();
function hubFor(document: Document) {
  let hub = hubs.get(document);
  if (!hub) {
    hub = createHub(document);
    hubs.set(document, hub);
  }
  return hub;
}

function canFocusDropTarget(element: HTMLElement) {
  if (element.closest('[inert], [hidden], [aria-hidden="true"], [aria-disabled="true"]')) return false;
  if (element.matches(':disabled')) return false;
  if (!element.hasAttribute('tabindex') && !element.matches('a[href],button,input,textarea,select'))
    return false;
  for (let ancestor: HTMLElement | null = element; ancestor; ancestor = ancestor.parentElement) {
    const style = getComputedStyle(ancestor);
    if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse')
      return false;
  }
  return true;
}

function createHub(document: Document) {
  const surfaces = new Set<Surface>();
  const session = shallowRef<Session>();
  let frame = 0;
  let suppressTimer: ReturnType<typeof setTimeout> | undefined;
  let candidate: { surface: Surface; event: UiTreeDropEvent } | undefined;
  let enteredSurface: Surface | undefined;
  function clearFeedback() {
    surfaces.forEach((surface) => surface.feedback());
    candidate = undefined;
  }
  function targetAt(origin: Element | null, nativeEvent?: Event) {
    candidate = undefined;
    const state = session.value;
    const source = state?.source();
    if (!state || !source) {
      finish(true);
      return;
    }
    if (!origin) {
      if (enteredSurface?.cancelWhenPointerLeaves) finish(true);
      else clearFeedback();
      return;
    }
    const surface = [...surfaces]
      .filter((item) => item.root.contains(origin))
      .sort((a, b) => (a.root.contains(b.root) ? 1 : -1))[0];
    if (enteredSurface?.cancelWhenPointerLeaves && !enteredSurface.root.contains(origin)) {
      finish(true);
      return;
    }
    if (!surface) {
      clearFeedback();
      return;
    }
    // Entering a preview transfers its leave/cancel boundary to this drag, including
    // drags that began in an external palette without such a boundary.
    enteredSurface = surface;
    surfaces.forEach((item) => {
      if (item !== surface) item.feedback();
    });
    if (!surface) return;
    const target = surface.resolve(origin, state.y, state.position, source, state.x);
    if (!target) {
      surface.feedback();
      return;
    }
    const operation = surface.operation?.(source) ?? source.operations[0];
    if (!operation) {
      surface.feedback();
      return;
    }
    const event: UiTreeDropEvent = { source, target, operation, nativeEvent };
    const accepted = source.operations.includes(operation) && surface.allow(event);
    surface.feedback(target, !accepted);
    if (accepted) candidate = { surface, event };
  }
  function hit(event: MouseEvent) {
    return (
      document.elementFromPoint?.(event.clientX, event.clientY) ??
      (event.target instanceof Element ? event.target : null)
    );
  }
  function move(event: MouseEvent) {
    const state = session.value;
    if (!state || state.keyboard) return;
    if (event.buttons !== 1) {
      finish(true);
      return;
    }
    state.x = event.clientX;
    state.y = event.clientY;
    if (!state.started) {
      if (Math.hypot(state.x - state.startX, state.y - state.startY) < 4) return;
      const source = state.source();
      if (!source) {
        finish(true);
        return;
      }
      state.started = true;
      session.value = { ...state };
      state.start?.(source);
      frame = requestAnimationFrame(scroll);
    }
    targetAt(hit(event), event);
    event.preventDefault();
  }
  function scroll() {
    const state = session.value;
    if (!state || state.keyboard || !state.started) return;
    let element = document.elementFromPoint?.(state.x, state.y);
    let scrolledX = false;
    let scrolledY = false;
    for (let parent = element; parent; parent = parent.parentElement) {
      const style = getComputedStyle(parent);
      const rect = parent.getBoundingClientRect();
      if (!scrolledX && /(auto|scroll)/.test(style.overflowX) && parent.scrollWidth > parent.clientWidth) {
        const dx = state.x < rect.left + 28 ? -10 : state.x > rect.right - 28 ? 10 : 0;
        const before = parent.scrollLeft;
        parent.scrollLeft += dx;
        scrolledX = parent.scrollLeft !== before;
      }
      if (!scrolledY && /(auto|scroll)/.test(style.overflowY) && parent.scrollHeight > parent.clientHeight) {
        const dy = state.y < rect.top + 28 ? -10 : state.y > rect.bottom - 28 ? 10 : 0;
        const before = parent.scrollTop;
        parent.scrollTop += dy;
        scrolledY = parent.scrollTop !== before;
      }
      // Each axis scrolls its nearest movable ancestor; an exhausted inner scrollport may yield outward.
      if (scrolledX && scrolledY) break;
    }
    // Pointer movement already performs hit testing. Re-resolving every animation frame creates
    // a permanent update loop for preview surfaces; only scrolling changes the pointer geometry.
    if (scrolledX || scrolledY) {
      element = document.elementFromPoint?.(state.x, state.y);
      if (element) targetAt(element);
    }
    if (session.value) frame = requestAnimationFrame(scroll);
  }
  function preventClick(event: Event) {
    if (event instanceof MouseEvent && event.detail > 0) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  }
  function finish(cancelled: boolean) {
    const old = session.value;
    session.value = undefined;
    enteredSurface = undefined;
    cancelAnimationFrame(frame);
    clearFeedback();
    document.removeEventListener('mousemove', move);
    document.removeEventListener('mouseup', release);
    document.removeEventListener('contextmenu', contextmenu, true);
    document.removeEventListener('keydown', keydown, true);
    document.defaultView?.removeEventListener('blur', blur);
    if (old?.started && !old.keyboard) {
      document.addEventListener('click', preventClick, true);
      document.addEventListener('dblclick', preventClick, true);
      if (suppressTimer) clearTimeout(suppressTimer);
      suppressTimer = setTimeout(() => {
        document.removeEventListener('click', preventClick, true);
        document.removeEventListener('dblclick', preventClick, true);
      }, 0);
    }
    if (old?.started) old.end?.(cancelled);
  }
  function commit(event: Event) {
    const accepted = candidate;
    finish(!accepted);
    if (accepted) accepted.surface.drop({ ...accepted.event, nativeEvent: event });
  }
  function release(event: MouseEvent) {
    if (!session.value?.started) {
      finish(true);
      return;
    }
    const state = session.value;
    state.x = event.clientX;
    state.y = event.clientY;
    targetAt(hit(event), event);
    commit(event);
  }
  function blur() {
    finish(true);
  }
  function contextmenu(event: MouseEvent) {
    if (!session.value?.started) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    finish(true);
  }
  function keydown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopImmediatePropagation();
      finish(true);
      return;
    }
    const state = session.value;
    if (!state?.keyboard) return;
    if (event.key === 'Tab') return;
    if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter', ' '].includes(event.key)) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    const targets = [
      ...document.querySelectorAll<HTMLElement>('[data-ui-drop-key], [data-ui-drop-root]'),
    ].filter(
      (element) =>
        [...surfaces].some((surface) => surface.root.contains(element)) && canFocusDropTarget(element),
    );
    const focusedTarget = document.activeElement?.closest<HTMLElement>(
      '[data-ui-drop-key], [data-ui-drop-root]',
    );
    const index = targets.indexOf(focusedTarget as HTMLElement);
    if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
      targets[(index + (event.key === 'ArrowDown' ? 1 : -1) + targets.length) % targets.length]?.focus();
    }
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      const positions: UiDropPosition[] = ['before', 'inside', 'after'];
      state.position =
        positions[(positions.indexOf(state.position ?? 'inside') + (event.key === 'ArrowRight' ? 1 : 2)) % 3];
    }
    targetAt(document.activeElement, event);
    if (event.key === 'Enter' || event.key === ' ') commit(event);
  }
  function begin(next: Session) {
    finish(true);
    session.value = next;
    document.addEventListener('mousemove', move);
    document.addEventListener('mouseup', release);
    document.addEventListener('contextmenu', contextmenu, true);
    document.addEventListener('keydown', keydown, true);
    document.defaultView?.addEventListener('blur', blur);
    if (next.keyboard) {
      const source = next.source();
      if (source) next.start?.(source);
      targetAt(document.activeElement);
    }
  }
  return { surfaces, session, begin, finish, clearFeedback };
}

/** Shared by trees, flat lists and non-tree drop surfaces. No browser payload leaks to consumers. */
export function useUiDropTarget(
  root: Ref<HTMLElement | undefined>,
  options: Omit<Surface, 'root' | 'feedback'>,
) {
  const hovered = shallowRef<UiDropTarget>();
  const rejected = ref(false);
  let surface: Surface | undefined;
  let hub: ReturnType<typeof createHub> | undefined;
  function dispose() {
    if (surface && hub) {
      if (hovered.value) hub.finish(true);
      hub.surfaces.delete(surface);
    }
    surface = undefined;
    hovered.value = undefined;
  }
  watch(
    root,
    (element) => {
      dispose();
      if (!element) return;
      hub = hubFor(element.ownerDocument);
      surface = {
        ...options,
        root: element,
        feedback: (target, denied) => {
          const previous = hovered.value;
          if (
            previous?.kind !== target?.kind ||
            previous?.instanceId !== target?.instanceId ||
            previous?.position !== target?.position ||
            (previous?.kind === 'node' ? previous.node.key : undefined) !==
              (target?.kind === 'node' ? target.node.key : undefined)
          )
            hovered.value = target;
          rejected.value = denied ?? false;
        },
      };
      hub.surfaces.add(surface);
    },
    { flush: 'post', immediate: true },
  );
  onBeforeUnmount(dispose);
  return {
    hovered,
    rejected,
    // Receivers need session lifetime even when the source belongs to another surface.
    draggingSource: computed(() => {
      const state = root.value && hubFor(root.value.ownerDocument).session.value;
      return state?.started ? state.source() : undefined;
    }),
    clear: () => {
      hovered.value = undefined;
      rejected.value = false;
    },
  };
}

export function useUiDragSource(
  root: Ref<HTMLElement | undefined>,
  instanceId: string,
  source: (key: string) => UiDragSource | undefined,
  callbacks: { start: (source: UiDragSource) => void; end: (cancelled: boolean) => void },
) {
  const hub = hubFor(document);
  const draggingKey = computed(() => {
    const current = hub.session.value;
    const active = current?.source();
    return current?.started && active?.instanceId === instanceId ? active.node.key : undefined;
  });
  function begin(key: string, event: MouseEvent | KeyboardEvent) {
    if (!source(key)) return;
    if (
      event.target instanceof Element &&
      event.target.closest('button,input,textarea,select,a,[contenteditable="true"]')
    )
      return;
    const keyboard = event instanceof KeyboardEvent;
    if (!keyboard && event.button !== 0) return;
    if (keyboard) {
      event.preventDefault();
      event.stopPropagation();
    }
    hub.begin({
      owner: instanceId,
      source: () => (root.value ? source(key) : undefined),
      keyboard,
      started: keyboard,
      x: keyboard ? 0 : event.clientX,
      y: keyboard ? 0 : event.clientY,
      startX: keyboard ? 0 : event.clientX,
      startY: keyboard ? 0 : event.clientY,
      start: callbacks.start,
      end: callbacks.end,
    });
  }
  onBeforeUnmount(() => {
    if (hub.session.value?.owner === instanceId) hub.finish(true);
  });
  return { begin, draggingKey };
}
