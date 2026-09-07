import { computed, shallowRef, useId, watch, type Ref } from 'vue';
import {
  useUiDragSource,
  useUiDropTarget,
  type UiDragSource,
  type UiTreeDropEvent,
} from '@muyun/vue-ui-antdv';
import {
  PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE,
  parsePageCompositionDragPayload,
} from './pageCompositionDragPayload';

const ACTION_DRAG_TYPE = 'page-composition-action-placement';
type Anchor = 'page' | 'detail' | 'form';

/** A small, isolated drag surface for one preview action bar. It deliberately owns no field placement. */
export function usePageCompositionActionPreviewDrag(
  root: Ref<HTMLElement | undefined>,
  anchor: Anchor,
  actions: Ref<{ actionCode: string; title?: string }[]>,
  enabled: Ref<boolean>,
  canDrop: (source: { actionCode: string }) => boolean,
  onDrop: (
    source: { actionCode: string; sourceAnchor?: Anchor },
    target: { anchor: Anchor; index: number },
  ) => void,
) {
  const instanceId = useId();
  const transientPlacement = shallowRef<{ actionCode: string; index: number }>();
  let committingPlacement = false;
  type FrozenGeometry = {
    rect: DOMRect;
    scrollPositions: Map<HTMLElement, { left: number; top: number }>;
  };
  const geometry = new Map<string, FrozenGeometry>();
  let geometryCaptured = false;
  function scrollPositionsOf(element: HTMLElement) {
    const positions = new Map<HTMLElement, { left: number; top: number }>();
    for (let parent = element.parentElement; parent; parent = parent.parentElement)
      positions.set(parent, { left: parent.scrollLeft, top: parent.scrollTop });
    return positions;
  }
  function currentFrozenRect(frozen: FrozenGeometry) {
    let xOffset = 0;
    let yOffset = 0;
    for (const [element, start] of frozen.scrollPositions) {
      xOffset += element.scrollLeft - start.left;
      yOffset += element.scrollTop - start.top;
    }
    const { rect } = frozen;
    return {
      ...rect,
      x: rect.x - xOffset,
      y: rect.y - yOffset,
      left: rect.left - xOffset,
      right: rect.right - xOffset,
      top: rect.top - yOffset,
      bottom: rect.bottom - yOffset,
    } as DOMRect;
  }
  function captureGeometry() {
    if (!root.value || geometryCaptured) return;
    geometryCaptured = true;
    for (const element of root.value.querySelectorAll<HTMLElement>('[data-page-action-key]')) {
      const key = element.dataset.pageActionKey;
      if (key)
        geometry.set(key, {
          rect: element.getBoundingClientRect(),
          scrollPositions: scrollPositionsOf(element),
        });
    }
  }
  function frozenTargetAt(x: number | undefined, y: number) {
    if (!geometryCaptured || x === undefined) return;
    return [...geometry.entries()]
      .map(([key, frozen]) => [key, currentFrozenRect(frozen)] as const)
      .find(([, rect]) => x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom);
  }
  function clearGeometry() {
    geometry.clear();
    geometryCaptured = false;
  }
  function sourceOf(source: UiDragSource) {
    if (source.payloadType === ACTION_DRAG_TYPE) {
      const value = source.payload as { actionCode?: unknown; sourceAnchor?: Anchor } | undefined;
      return typeof value?.actionCode === 'string'
        ? { actionCode: value.actionCode, sourceAnchor: value.sourceAnchor }
        : undefined;
    }
    if (source.payloadType !== PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE) return undefined;
    const value = parsePageCompositionDragPayload(source.payload);
    return value?.kind === 'action' ? { actionCode: value.actionCode } : undefined;
  }
  function targetOf(
    key: string | undefined,
    position: 'before' | 'inside' | 'after',
    sourceActionCode?: string,
  ) {
    const available = actions.value.filter((action) => action.actionCode !== sourceActionCode);
    const index = key == null ? available.length : available.findIndex((action) => action.actionCode === key);
    return { anchor, index: index < 0 ? available.length : index + (position === 'after' ? 1 : 0) };
  }
  function preferredPosition(sourceActionCode: string | undefined, targetActionCode: string) {
    if (!sourceActionCode) return 'before' as const;
    const sourceIndex = actions.value.findIndex((action) => action.actionCode === sourceActionCode);
    const targetIndex = actions.value.findIndex((action) => action.actionCode === targetActionCode);
    return sourceIndex >= 0 && targetIndex >= 0 && sourceIndex < targetIndex
      ? ('after' as const)
      : ('before' as const);
  }
  const { hovered, rejected, clear } = useUiDropTarget(root, {
    // Entries can cross action bars; leaving a bar clears its preview without cancelling the gesture.
    cancelWhenPointerLeaves: false,
    resolve(origin, _y, position, _source, x) {
      const target = origin.closest<HTMLElement>('[data-page-action-key]');
      const liveKey = target?.dataset.pageActionKey;
      const source = _source && sourceOf(_source);
      const isLiveSource = source?.sourceAnchor === anchor && source.actionCode === liveKey;
      // A staged layout moves the buttons below the held pointer. Follow the live button for an
      // internal move, and reserve the initial geometry only for source/gap fallback and palette
      // actions, whose temporary button has not existed at capture time.
      const preferLiveTarget = _source?.payloadType === ACTION_DRAG_TYPE && Boolean(liveKey) && !isLiveSource;
      const frozen = preferLiveTarget ? undefined : frozenTargetAt(x, _y);
      const key = preferLiveTarget ? liveKey : (frozen?.[0] ?? liveKey);
      const rect = frozen?.[1] ?? target?.getBoundingClientRect() ?? root.value?.getBoundingClientRect();
      if (!rect) return;
      if (isLiveSource) return;
      const ratio = ((x ?? rect.left + rect.width / 2) - rect.left) / Math.max(rect.width, 1);
      const dropPosition: 'before' | 'after' =
        ratio < 0.24
          ? 'before'
          : ratio > 0.76
            ? 'after'
            : key
              ? preferredPosition(source?.actionCode, key)
              : 'before';
      const resolved = {
        instanceId,
        kind: 'node' as const,
        node: { key: key ?? `anchor:${anchor}`, title: key ?? '动作区域' },
        position: (key ? dropPosition : 'inside') as 'before' | 'after' | 'inside',
      };
      if (source && enabled.value && canDrop(source)) {
        captureGeometry();
        const next = {
          actionCode: source.actionCode,
          index: targetOf(key, resolved.position, source.actionCode).index,
        };
        if (
          transientPlacement.value?.actionCode !== next.actionCode ||
          transientPlacement.value.index !== next.index
        )
          transientPlacement.value = next;
      } else if (transientPlacement.value) transientPlacement.value = undefined;
      return resolved;
    },
    allow: (event) => {
      const source = sourceOf(event.source);
      return enabled.value && Boolean(source && canDrop(source));
    },
    operation: (source) => (source.payloadType === ACTION_DRAG_TYPE ? 'move' : 'copy'),
    drop: (event: UiTreeDropEvent) => {
      const source = sourceOf(event.source);
      if (source && enabled.value && canDrop(source) && event.target.kind === 'node') {
        committingPlacement = true;
        onDrop(
          source,
          targetOf(
            event.target.node.key.startsWith('anchor:') ? undefined : event.target.node.key,
            event.target.position,
            source.actionCode,
          ),
        );
      }
    },
  });
  const { begin, draggingKey } = useUiDragSource(
    root,
    instanceId,
    (key) => {
      if (!enabled.value || !actions.value.some((action) => action.actionCode === key)) return;
      return {
        instanceId,
        node: { key, title: actions.value.find((action) => action.actionCode === key)?.title ?? key },
        operations: ['move'],
        payloadType: ACTION_DRAG_TYPE,
        payload: { actionCode: key, sourceAnchor: anchor },
      };
    },
    {
      start: () => dragSourceElement?.classList.add('page-composer-action-drag-source'),
      end: () => {
        dragSourceElement?.classList.remove('page-composer-action-drag-source');
        dragSourceElement = undefined;
      },
    },
  );
  let dragSourceElement: HTMLElement | undefined;
  watch([enabled, hovered], ([isEnabled, target]) => {
    if (!isEnabled || !target) {
      if (!committingPlacement) {
        transientPlacement.value = undefined;
        if (!target) clearGeometry();
      }
    }
    if (!isEnabled) clear();
  });
  // The parent replaces the action list synchronously on a successful drop. Keep the staged list
  // through that replacement so Vue's FLIP state is continuous, then let the persisted list own it.
  watch(
    actions,
    () => {
      if (!committingPlacement) return;
      committingPlacement = false;
      transientPlacement.value = undefined;
      clearGeometry();
    },
    { flush: 'post' },
  );
  function dragHandleProps(actionCode: string, title: string) {
    return {
      tabindex: enabled.value ? 0 : -1,
      role: 'button',
      'aria-label': `拖动${title}`,
      title: `拖动${title}；空格开始，方向键定位，回车放置，Esc 取消`,
      class: {
        'page-composition-action-preview__drag-handle': true,
        'is-dragging': draggingKey.value === actionCode,
      },
      onMousedown: (event: MouseEvent) => {
        dragSourceElement =
          (event.currentTarget as HTMLElement | null)?.closest<HTMLElement>('[data-page-action-key]') ??
          undefined;
        begin(actionCode, event);
      },
      onKeydown: (event: KeyboardEvent) => {
        if (event.key === ' ') {
          dragSourceElement =
            (event.currentTarget as HTMLElement | null)?.closest<HTMLElement>('[data-page-action-key]') ??
            undefined;
          begin(actionCode, event);
        }
      },
    };
  }
  const feedback = computed(() => {
    const target = hovered.value;
    if (!target || target.kind !== 'node') return undefined;
    const key = target.node.key.startsWith('anchor:') ? undefined : target.node.key;
    return { key, position: target.position, rejected: rejected.value };
  });
  const stagedActionCodes = computed(() => {
    const placement = transientPlacement.value;
    if (!placement) return actions.value.map((action) => action.actionCode);
    const without = actions.value.filter((action) => action.actionCode !== placement.actionCode);
    const index = Math.max(0, Math.min(placement.index, without.length));
    return [
      ...without.slice(0, index).map((action) => action.actionCode),
      placement.actionCode,
      ...without.slice(index).map((action) => action.actionCode),
    ];
  });
  return {
    dragHandleProps,
    dragging: computed(() => draggingKey.value),
    feedback,
    transientPlacement: computed(() => transientPlacement.value),
    stagedActionCodes,
  };
}
