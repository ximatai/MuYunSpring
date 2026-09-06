import { computed, ref, useId, watch, type Ref } from 'vue';
import {
  useUiDragSource,
  useUiDropTarget,
  type UiDragSource,
  type UiDropPosition,
  type UiTreeDropEvent,
} from '@muyun/vue-ui-antdv';
import { PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE, parseMetadataDragPayload } from './pageCompositionDragPayload';
import {
  PAGE_COMPOSITION_NODE_DRAG_TYPE,
  resolveCompositionPlacement,
  type CompositionContainer,
  type CompositionPlacementSource,
  type CompositionPlacementTarget,
  type PageCompositionStructure,
} from './pageCompositionPlacement';

export interface PreviewPlacementEntry {
  title: string;
  container: CompositionContainer;
  nodeId?: string;
  /** Field receivers on a group/child-table heading, distinct from moving that whole section. */
  inside?: CompositionContainer;
  axis?: 'x' | 'y';
}

/** Coordinates and feedback belong to the preview adapter; placement legality belongs to the draft. */
export function usePageCompositionPreviewDrag(
  root: Ref<HTMLElement | undefined>,
  entries: Ref<Map<string, PreviewPlacementEntry>>,
  structure: Ref<PageCompositionStructure>,
  enabled: Ref<boolean>,
  onDrop: (source: CompositionPlacementSource, target: CompositionPlacementTarget) => void,
) {
  const instanceId = useId();
  const indicator = ref<{ left: number; top: number; width: number; height: number }>();
  function sourceOf(source: UiDragSource): CompositionPlacementSource | undefined {
    if (source.instanceId === instanceId && source.payloadType === PAGE_COMPOSITION_NODE_DRAG_TYPE) {
      const entry = entries.value.get(source.node.key);
      return entry?.nodeId ? { kind: 'node', container: entry.container, nodeId: entry.nodeId } : undefined;
    }
    const metadata =
      source.payloadType === PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE && parseMetadataDragPayload(source.payload);
    return metadata ? { kind: 'metadata', metadata } : undefined;
  }
  function targetOf(key: string, position: UiDropPosition, source: CompositionPlacementSource) {
    const entry = entries.value.get(key);
    if (!entry) return;
    const field =
      source.kind === 'metadata'
        ? source.metadata.kind !== 'relation'
        : ['list', 'form', 'group', 'relation'].includes(source.container.kind);
    if (entry.inside && field) return { container: entry.inside, position: 'inside' as const };
    return { container: entry.container, anchorId: entry.nodeId, position };
  }
  function placement(event: UiTreeDropEvent) {
    if (!enabled.value || event.target.kind !== 'node') return;
    const source = sourceOf(event.source);
    const target = source && targetOf(event.target.node.key, event.target.position, source);
    return source && target && resolveCompositionPlacement(structure.value, source, target)
      ? { source, target }
      : undefined;
  }
  const { hovered, rejected, clear } = useUiDropTarget(root, {
    resolve(origin, y, position, source, x) {
      const element = origin.closest<HTMLElement>(
        '[data-composer-target], [data-page-composition-layout-key]',
      );
      const key = element?.dataset.composerTarget ?? element?.dataset.pageCompositionLayoutKey;
      const entry = key && entries.value.get(key);
      if (!element || !key || !entry || !source) return;
      const rect = element.getBoundingClientRect();
      const horizontal = entry.axis === 'x';
      const before = horizontal
        ? (x ?? rect.left) < rect.left + rect.width / 2
        : y < rect.top + rect.height / 2;
      const dropPosition = !entry.nodeId
        ? 'inside'
        : position === 'before' || position === 'after'
          ? position
          : before
            ? 'before'
            : 'after';
      const parsed = sourceOf(source);
      const target = parsed && targetOf(key, dropPosition, parsed);
      const inside = target?.position === 'inside';
      const rootRect = root.value!.getBoundingClientRect();
      indicator.value = {
        left:
          rect.left -
          rootRect.left +
          root.value!.scrollLeft +
          (!inside && horizontal && dropPosition === 'after' ? rect.width : 0),
        top:
          rect.top -
          rootRect.top +
          root.value!.scrollTop +
          (!inside && !horizontal && dropPosition === 'after' ? rect.height : 0),
        width: !inside && horizontal ? 3 : rect.width,
        height: !inside && !horizontal ? 3 : rect.height,
      };
      return {
        instanceId,
        kind: 'node',
        node: { key, title: entry.title },
        position: inside ? 'inside' : dropPosition,
      };
    },
    allow: (event) => !!placement(event),
    operation: (source) => (source.instanceId === instanceId ? 'move' : 'copy'),
    drop: (event) => {
      const result = placement(event);
      if (result) onDrop(result.source, result.target);
    },
  });
  const { begin, draggingKey } = useUiDragSource(
    root,
    instanceId,
    (key) => {
      const entry = entries.value.get(key);
      if (!enabled.value || !entry?.nodeId) return;
      return {
        instanceId,
        node: { key, title: entry.title },
        operations: ['move'],
        payloadType: PAGE_COMPOSITION_NODE_DRAG_TYPE,
      };
    },
    { start: () => undefined, end: () => undefined },
  );
  watch(enabled, clear);
  function handleProps(key: string, title: string) {
    return {
      role: 'button',
      tabindex: enabled.value ? 0 : -1,
      'aria-label': `拖动${title}`,
      'aria-disabled': !enabled.value,
      title: `拖动${title}；空格开始，方向键定位，回车放置，Esc 取消`,
      'data-composer-drag': key,
      class: 'page-composer-drag-handle',
      onMousedown: (event: MouseEvent) => {
        event.stopPropagation();
        begin(key, event);
      },
      onKeydown: (event: KeyboardEvent) => {
        if (event.key === ' ') {
          event.stopPropagation();
          begin(key, event);
        }
      },
      onClick: (event: MouseEvent) => {
        event.preventDefault();
        event.stopPropagation();
      },
    };
  }
  return {
    handleProps,
    draggingKey,
    feedback: computed(() =>
      hovered.value && indicator.value
        ? {
            ...indicator.value,
            rejected: rejected.value,
            title: rejected.value
              ? '不能放置在此处'
              : hovered.value.kind === 'node'
                ? `放置：${hovered.value.node.title}${hovered.value.position === 'before' ? '之前' : hovered.value.position === 'after' ? '之后' : '内部'}`
                : '',
          }
        : undefined,
    ),
  };
}
