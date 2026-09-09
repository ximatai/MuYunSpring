import { computed, onBeforeUnmount, ref, shallowRef, useId, watch, type Ref } from 'vue';
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
  containerKey,
  compositionItems,
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
  axis?: 'x' | 'y' | 'grid';
  region?: string;
}

export interface PageCompositionPreviewPlacement {
  source: CompositionPlacementSource;
  target: CompositionPlacementTarget;
  placement: { container: CompositionContainer; index: number };
  title: string;
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
  const transientPlacement = shallowRef<PageCompositionPreviewPlacement>();
  let committingPlacement = false;
  let directionalIntent:
    | { key: string; source: CompositionPlacementSource; position: 'before' | 'after' }
    | undefined;
  function sameSource(
    left: CompositionPlacementSource | undefined,
    right: CompositionPlacementSource | undefined,
  ) {
    if (!left || !right || left.kind !== right.kind) return false;
    if (left.kind === 'node' && right.kind === 'node') return left.nodeId === right.nodeId;
    if (left.kind !== 'metadata' || right.kind !== 'metadata') return false;
    const [source, candidate] = [left.metadata, right.metadata];
    if (source.kind !== candidate.kind) return false;
    if (source.kind === 'field' && candidate.kind === 'field') return source.fieldId === candidate.fieldId;
    if (source.kind === 'relation' && candidate.kind === 'relation')
      return source.relationId === candidate.relationId;
    return source.kind === 'relationField' && candidate.kind === 'relationField'
      ? source.relationId === candidate.relationId && source.fieldId === candidate.fieldId
      : false;
  }
  function samePlacement(
    left: PageCompositionPreviewPlacement | undefined,
    right: PageCompositionPreviewPlacement,
  ) {
    return (
      sameSource(left?.source, right.source) &&
      containerKey(left?.placement.container ?? right.placement.container) ===
        containerKey(right.placement.container) &&
      left?.placement.index === right.placement.index
    );
  }
  /**
   * Once the preview starts rendering its candidate layout, the DOM no longer describes the
   * pointer's intended target. Keep the pre-drag geometry for the rest of this drag session so
   * an item moving underneath the pointer cannot select itself (and make the layout oscillate).
   */
  type FrozenGeometry = { rect: DOMRect; scrollPositions: Map<HTMLElement, { left: number; top: number }> };
  const placementGeometry = new Map<string, FrozenGeometry>();
  let placementGeometryCaptured = false;
  const appendGeometry = new Map<
    string,
    { key: string; entry: PreviewPlacementEntry; geometry: FrozenGeometry }
  >();
  // The first staged insertion creates a real extra cell. Capture its layout destination, not
  // its animated screen position, and retain it for the drag just like the original cells.
  watch(
    transientPlacement,
    (staged) => {
      if (staged?.source.kind !== 'metadata' || staged.source.metadata.kind !== 'field' || !root.value)
        return;
      const metadata = staged.source.metadata;
      const container = staged.placement.container;
      const containerId = containerKey(container);
      const items = compositionItems(structure.value, container);
      if (!items?.length || items.some((item) => item.id === metadata.fieldId)) return;
      const region =
        container.kind === 'form'
          ? `root:${items.slice(0, staged.placement.index).filter((item) => 'groupCode' in item).length}`
          : containerId;
      const id = `${containerId}:${region}`;
      if (appendGeometry.has(id)) return;
      const fieldName = metadata.fieldName;
      const candidates = [
        ...root.value.querySelectorAll<HTMLElement>('[data-page-composition-layout-key]'),
      ].filter((element) => {
        const key = element.dataset.pageCompositionLayoutKey!;
        const entry = entries.value.get(key);
        return (
          (entry?.axis === 'grid' &&
            containerKey(entry.container) === containerId &&
            entry.region === region) ||
          Boolean(fieldName && key.endsWith(`:field:${fieldName}`))
        );
      });
      const last = candidates.at(-1);
      const anchor = [...entries.value]
        .filter(
          ([, entry]) =>
            entry.axis === 'grid' && containerKey(entry.container) === containerId && entry.region === region,
        )
        .at(-1);
      if (!last || !anchor) return;
      const bounds = last.getBoundingClientRect();
      if (bounds.width <= 0 || bounds.height <= 0) return;
      const transform = getComputedStyle(last).transform;
      const translation = !transform || transform === 'none' ? undefined : new DOMMatrixReadOnly(transform);
      const rect = new DOMRect(
        bounds.left - (translation?.m41 ?? 0),
        bounds.top - (translation?.m42 ?? 0),
        bounds.width,
        bounds.height,
      );
      appendGeometry.set(id, {
        key: anchor[0],
        entry: anchor[1],
        geometry: { rect, scrollPositions: scrollPositionsOf(last) },
      });
    },
    { flush: 'post' },
  );
  function scrollPositionsOf(element: HTMLElement) {
    const positions = new Map<HTMLElement, { left: number; top: number }>();
    for (let parent = element.parentElement; parent; parent = parent.parentElement)
      positions.set(parent, { left: parent.scrollLeft, top: parent.scrollTop });
    return positions;
  }
  function currentFrozenRect(geometry: FrozenGeometry) {
    let xOffset = 0;
    let yOffset = 0;
    for (const [element, start] of geometry.scrollPositions) {
      xOffset += element.scrollLeft - start.left;
      yOffset += element.scrollTop - start.top;
    }
    const { rect } = geometry;
    return {
      ...rect,
      x: rect.x - xOffset,
      y: rect.y - yOffset,
      left: rect.left - xOffset,
      right: rect.right - xOffset,
      top: rect.top - yOffset,
      bottom: rect.bottom - yOffset,
      width: rect.width,
      height: rect.height,
    } as DOMRect;
  }
  function capturePlacementGeometry() {
    if (!root.value || placementGeometryCaptured) return;
    placementGeometryCaptured = true;
    for (const element of root.value.querySelectorAll<HTMLElement>(
      '[data-composer-target], [data-page-composition-layout-key]',
    )) {
      const key = element.dataset.composerTarget ?? element.dataset.pageCompositionLayoutKey;
      if (!key) continue;
      const rect = element.getBoundingClientRect();
      const previous = placementGeometry.get(key);
      // Renderers may repeat a layout key on nested content. The outermost usable receiver is the
      // one with the largest area; never let an inner label/input replace its hit rectangle.
      if (!previous || rect.width * rect.height > previous.rect.width * previous.rect.height)
        placementGeometry.set(key, { rect, scrollPositions: scrollPositionsOf(element) });
    }
    // Native form rendering has no wrapper around a group. Give its body one semantic
    // receiver, spanning the heading-to-next-section interval, including vertical whitespace.
    // Freeze it with the other slots: inserting the first field must not shrink the target.
    const geometryEntries = [...placementGeometry];
    for (const [headingKey, heading] of geometryEntries) {
      const group = entries.value.get(headingKey)?.inside;
      if (group?.kind !== 'group') continue;
      const key = [...entries.value].find(
        ([, entry]) => !entry.nodeId && containerKey(entry.container) === containerKey(group),
      )?.[0];
      if (!key) continue;
      const ownBottom = Math.max(
        heading.rect.bottom,
        ...geometryEntries
          .filter(
            ([item]) =>
              containerKey(entries.value.get(item)?.container ?? { kind: 'form' }) === containerKey(group),
          )
          .map(([, geometry]) => geometry.rect.bottom),
      );
      const nextTop = geometryEntries
        .filter(([item, geometry]) => {
          const entry = entries.value.get(item);
          return (
            entry?.nodeId &&
            ['form', 'groups', 'relations'].includes(entry.container.kind) &&
            geometry.rect.top > heading.rect.top + 1
          );
        })
        .map(([, geometry]) => geometry.rect.top);
      const bottom = nextTop.length
        ? Math.min(...nextTop)
        : Math.min(root.value.getBoundingClientRect().bottom, ownBottom + 16);
      if (bottom <= heading.rect.bottom) continue;
      placementGeometry.set(key, {
        rect: new DOMRect(
          heading.rect.left,
          heading.rect.bottom,
          heading.rect.width,
          bottom - heading.rect.bottom,
        ),
        scrollPositions: heading.scrollPositions,
      });
    }
  }
  function largestLiveRect(key: string, fallback: HTMLElement | null | undefined) {
    let largest = fallback?.getBoundingClientRect();
    if (!root.value) return largest;
    for (const candidate of root.value.querySelectorAll<HTMLElement>(
      '[data-composer-target], [data-page-composition-layout-key]',
    )) {
      const candidateKey = candidate.dataset.composerTarget ?? candidate.dataset.pageCompositionLayoutKey;
      if (candidateKey !== key) continue;
      const rect = candidate.getBoundingClientRect();
      if (!largest || rect.width * rect.height > largest.width * largest.height) largest = rect;
    }
    return largest;
  }
  /**
   * A list header and its content live in separate tables. Their cells are the actual receiver
   * slots, while the marker inside each cell is only the stable placement identity. Undo a
   * running FLIP translation here so the slot boundary reflects the reflowed table layout rather
   * than whichever column is visually passing under the pointer this frame.
   */
  function listSlotRect(key: string, fallback: HTMLElement | null | undefined) {
    const cells = new Set<HTMLElement>();
    const addCell = (element: HTMLElement | null | undefined) => {
      if (element) cells.add(element.closest<HTMLElement>('th, td') ?? element);
    };
    addCell(fallback);
    root.value
      ?.querySelectorAll<HTMLElement>(`[data-page-composition-layout-key="${CSS.escape(key)}"]`)
      .forEach(addCell);
    let largest: DOMRect | undefined;
    for (const cell of cells) {
      const bounds = cell.getBoundingClientRect();
      const transform = getComputedStyle(cell).transform;
      const translation = !transform || transform === 'none' ? undefined : new DOMMatrixReadOnly(transform);
      const rect = new DOMRect(
        bounds.left - (translation?.m41 ?? 0),
        bounds.top - (translation?.m42 ?? 0),
        bounds.width,
        bounds.height,
      );
      if (!largest || rect.width * rect.height > largest.width * largest.height) largest = rect;
    }
    return largest;
  }
  function listSlotAt(x: number | undefined, y: number) {
    if (x === undefined) return;
    const slots = [...entries.value].flatMap(([key, entry]) => {
      if (entry.container.kind !== 'list' || entry.axis !== 'x' || !entry.nodeId) return [];
      const rect = listSlotRect(key, undefined);
      return rect?.width && rect.height ? [{ key, rect }] : [];
    });
    const distance = ({ rect }: (typeof slots)[number]) =>
      Math.max(rect.left - x, 0, x - rect.right) ** 2 + Math.max(rect.top - y, 0, y - rect.bottom) ** 2;
    return slots.sort((left, right) => distance(left) - distance(right))[0];
  }
  function groupSlotAt(source: CompositionPlacementSource | undefined, x: number | undefined, y: number) {
    if (source?.kind !== 'node' || source.container.kind !== 'groups' || x === undefined) return;
    const items = compositionItems(structure.value, { kind: 'form' }) ?? [];
    const remaining = items.filter((item) => item.id !== source.nodeId);
    for (const [key, geometry] of placementGeometry) {
      const entry = entries.value.get(key);
      if (!entry?.nodeId || !['form', 'groups'].includes(entry.container.kind)) continue;
      const rect = currentFrozenRect(geometry);
      let bottom = rect.bottom;
      if (entry.inside?.kind === 'group') {
        const bodyKey = [...entries.value].find(
          ([, candidate]) =>
            !candidate.nodeId && containerKey(candidate.container) === containerKey(entry.inside!),
        )?.[0];
        const body = bodyKey && placementGeometry.get(bodyKey);
        if (body) bottom = Math.max(bottom, currentFrozenRect(body).bottom);
      }
      if (x < rect.left || x > rect.right || y < rect.top || y >= bottom) continue;
      // A group moves as one slot, including its fields and whitespace. Only the outer edges
      // request explicit before/after; traversing its body occupies that position in either direction.
      let index = items.findIndex((item) => item.id === entry.nodeId);
      if (index < 0) continue;
      if (entry.nodeId !== source.nodeId && (y < rect.top + 6 || y > bottom - 6))
        index = remaining.findIndex((item) => item.id === entry.nodeId) + (y > bottom - 6 ? 1 : 0);
      const anchor = remaining[index] ?? remaining.at(-1);
      if (!anchor) return;
      return {
        key,
        rect: new DOMRect(rect.left, rect.top, rect.width, bottom - rect.top),
        target: {
          container: { kind: 'form' as const },
          anchorId: anchor.id,
          position: remaining[index] ? ('before' as const) : ('after' as const),
        },
      };
    }
  }
  function activeGroupAt(x: number | undefined, y: number) {
    const staged = transientPlacement.value;
    if (x === undefined || staged?.placement.container.kind !== 'group') return;
    const container = staged.placement.container;
    const key = [...entries.value].find(
      ([, entry]) => !entry.nodeId && containerKey(entry.container) === containerKey(container),
    )?.[0];
    const headingKey = [...entries.value].find(
      ([, entry]) => entry.inside && containerKey(entry.inside) === containerKey(container),
    )?.[0];
    const original = key && placementGeometry.get(key);
    const heading = headingKey && largestLiveRect(headingKey, undefined);
    if (!key || !original || !heading) return;
    const rect = currentFrozenRect(original);
    // A newly populated group can move upward. Keep the corridor between its original body
    // and its visible heading assigned to that group while the pointer follows the reflow.
    if (x < rect.left || x > rect.right || y < Math.min(rect.top, heading.bottom) || y > rect.bottom) return;
    return { key, rect, target: staged.target };
  }
  function frozenTargetAt(x: number | undefined, y: number) {
    if (!placementGeometryCaptured || x === undefined) return;
    return [...placementGeometry.entries()]
      .map(([key, geometry]) => [key, currentFrozenRect(geometry)] as const)
      .filter(([, rect]) => x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom)
      .sort(([, left], [, right]) => left.width * left.height - right.width * right.height)[0];
  }
  /** A grid slot is a stable location, even while FLIP moves a different card through it. */
  function gridSlotAt(source: CompositionPlacementSource | undefined, x: number | undefined, y: number) {
    if (!source || x === undefined || (source.kind === 'node' && source.container.kind === 'groups')) return;
    const sourceId =
      source.kind === 'node'
        ? source.nodeId
        : source.metadata.kind === 'field'
          ? source.metadata.fieldId
          : undefined;
    if (!sourceId) return;
    const slots = [...placementGeometry].flatMap(([key, geometry]) => {
      const entry = entries.value.get(key);
      return entry?.axis === 'grid' &&
        entry.nodeId &&
        (source.kind === 'node' && !['form', 'group'].includes(source.container.kind)
          ? containerKey(entry.container) === containerKey(source.container)
          : ['form', 'group'].includes(entry.container.kind))
        ? [{ key, entry, rect: currentFrozenRect(geometry), append: false }]
        : [];
    });
    if (source.kind === 'metadata') {
      for (const { key, entry, geometry } of appendGeometry.values())
        slots.push({ key, entry, rect: currentFrozenRect(geometry), append: true });
    }
    // Each form/group owns its own grid. Do not bridge unrelated sections with one bounding box.
    const grids = new Map<string, typeof slots>();
    for (const slot of slots) {
      const key = `${containerKey(slot.entry.container)}:${slot.entry.region ?? ''}`;
      const grid = grids.get(key) ?? [];
      grid.push(slot);
      grids.set(key, grid);
    }
    const receivers = [...grids.values()].find((grid) => {
      // An incomplete group row still owns the empty column beside its fields. Bound it by
      // the group's frozen heading, so a field entering that column cannot fall through to
      // the root form after the first transient reflow.
      const container = grid[0]!.entry.container;
      const heading =
        container.kind === 'group'
          ? [...placementGeometry].find(([key]) => {
              const inside = entries.value.get(key)?.inside;
              return inside?.kind === 'group' && inside.groupId === container.groupId;
            })?.[1]
          : undefined;
      const bounds = heading && currentFrozenRect(heading);
      return (
        x >= (bounds?.left ?? Math.min(...grid.map(({ rect }) => rect.left))) &&
        x <= (bounds?.right ?? Math.max(...grid.map(({ rect }) => rect.right))) &&
        y >= Math.min(...grid.map(({ rect }) => rect.top)) &&
        y <= Math.max(...grid.map(({ rect }) => rect.bottom))
      );
    });
    if (!receivers) return;
    const distance = ({ rect }: (typeof slots)[number]) =>
      Math.max(rect.left - x, 0, x - rect.right) ** 2 + Math.max(rect.top - y, 0, y - rect.bottom) ** 2;
    // Gutters belong to their nearest slot, not the enclosing form's append receiver.
    receivers.sort((left, right) => distance(left) - distance(right));
    for (const { key, entry, rect, append } of receivers) {
      const items = compositionItems(structure.value, entry.container) ?? [];
      const slot = items.findIndex((item) => item.id === entry.nodeId);
      if (slot < 0) continue;
      const remaining = items.filter((item) => item.id !== sourceId);
      const ratio = (x - rect.left) / Math.max(rect.width, 1);
      // Centre means occupy this slot in either direction. Edges retain explicit insertion
      // semantics; the source's original slot can also restore the initial order.
      let index = append ? remaining.findIndex((item) => item.id === entry.nodeId) + 1 : slot;
      if (!append && entry.nodeId !== sourceId && (ratio < 0.24 || ratio > 0.76))
        index = remaining.findIndex((item) => item.id === entry.nodeId) + (ratio > 0.76 ? 1 : 0);
      const anchor = remaining[index] ?? remaining.at(-1);
      if (!anchor) return { key, rect, target: { container: entry.container, position: 'inside' as const } };
      const target: CompositionPlacementTarget = {
        container: entry.container,
        anchorId: anchor.id,
        position: remaining[index] ? 'before' : 'after',
      };
      return { key, rect, target };
    }
  }
  function clearPlacementGeometry() {
    placementGeometry.clear();
    appendGeometry.clear();
    placementGeometryCaptured = false;
    directionalIntent = undefined;
  }
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
    if (entry.inside && field && (entry.container.kind === 'relations' || position === 'inside'))
      return { container: entry.inside, position: 'inside' as const };
    if (entry.container.kind === 'groups')
      return {
        container: { kind: 'form' as const },
        anchorId: entry.nodeId,
        position: position === 'inside' ? ('before' as const) : position,
      };
    return { container: entry.container, anchorId: entry.nodeId, position };
  }
  function preferredPosition(source: CompositionPlacementSource | undefined, entry: PreviewPlacementEntry) {
    if (
      source?.kind !== 'node' ||
      !entry.nodeId ||
      containerKey(source.container) !== containerKey(entry.container)
    )
      return 'after' as const;
    const items = compositionItems(structure.value, entry.container) ?? [];
    return items.findIndex((item) => item.id === source.nodeId) <
      items.findIndex((item) => item.id === entry.nodeId)
      ? ('after' as const)
      : ('before' as const);
  }
  function stabilizeDirectionalPosition(
    key: string,
    source: CompositionPlacementSource,
    position: 'before' | 'after',
    coordinate: number,
    size: number,
  ) {
    const previous = directionalIntent;
    if (previous?.key === key && sameSource(previous.source, source) && previous.position !== position) {
      const edge = position === 'after' ? size * 0.76 : size * 0.24;
      const hasCrossedHysteresis = position === 'after' ? coordinate > edge + 8 : coordinate < edge - 8;
      if (!hasCrossedHysteresis) return previous.position;
    }
    directionalIntent = { key, source, position };
    return position;
  }
  function placement(event: UiTreeDropEvent) {
    if (!enabled.value || event.target.kind !== 'node') return;
    const source = sourceOf(event.source);
    const target = source && targetOf(event.target.node.key, event.target.position, source);
    const resolved = source && target && resolveCompositionPlacement(structure.value, source, target);
    return source && target && resolved ? { source, target, resolved } : undefined;
  }
  const { hovered, rejected, clear, draggingSource } = useUiDropTarget(root, {
    cancelWhenPointerLeaves: true,
    resolve(origin, y, position, source, x) {
      const markedElement = origin.closest<HTMLElement>(
        '[data-composer-target], [data-page-composition-layout-key]',
      );
      // A table cell's padding is part of the visible list column but sits outside the heading or
      // field marker. Resolve it through the marker it contains, without making the surrounding
      // preview surface an implicit append receiver.
      const cellMarker = origin
        .closest<HTMLElement>('th, td')
        ?.querySelector<HTMLElement>('[data-page-composition-layout-key^="list:"]');
      const nearestElement = markedElement ?? cellMarker;
      const listTable = origin.closest('table')?.querySelector('[data-page-composition-layout-key^="list:"]');
      if (!source) return;
      const parsed = sourceOf(source);
      if (
        entries.value.get(source.node.key)?.axis === 'grid' ||
        (parsed?.kind === 'metadata' && parsed.metadata.kind === 'field')
      )
        capturePlacementGeometry();
      const gridSlot =
        position === undefined
          ? (groupSlotAt(parsed, x, y) ?? gridSlotAt(parsed, x, y) ?? activeGroupAt(x, y))
          : undefined;
      let element = nearestElement;
      let liveKey = element?.dataset.composerTarget ?? element?.dataset.pageCompositionLayoutKey;
      const nearestEntry = liveKey ? entries.value.get(liveKey) : undefined;
      const sourceIsListField =
        (parsed?.kind === 'node' && parsed.container.kind === 'list') ||
        (parsed?.kind === 'metadata' && parsed.metadata.kind === 'field');
      const listSlot =
        position === undefined &&
        sourceIsListField &&
        (nearestEntry?.container.kind === 'list' || liveKey?.startsWith('list:') || listTable)
          ? listSlotAt(x, y)
          : undefined;
      const nearestIsSource =
        (parsed?.kind === 'node' &&
          nearestEntry?.nodeId === parsed.nodeId &&
          containerKey(nearestEntry.container) === containerKey(parsed.container)) ||
        (parsed?.kind === 'metadata' &&
          parsed.metadata.kind === 'field' &&
          liveKey?.endsWith(`:field:${parsed.metadata.fieldName ?? ''}`));
      // Nested form shells expose both a field receiver and its group/container receiver. A
      // closest-only lookup can choose the child container for a group move, where that move is
      // illegal, and make a perfectly visible target intermittently fail. Walk outward until a
      // legal receiver is found; retain a source card itself as the neutral no-op target.
      if (parsed && !nearestIsSource) {
        for (
          let candidate = nearestElement;
          candidate && candidate !== root.value?.parentElement;
          candidate = candidate.parentElement
        ) {
          if (!candidate.matches('[data-composer-target], [data-page-composition-layout-key]')) continue;
          const candidateKey = candidate.dataset.composerTarget ?? candidate.dataset.pageCompositionLayoutKey;
          if (!candidateKey || !entries.value.has(candidateKey)) continue;
          const candidateTarget = targetOf(candidateKey, 'before', parsed);
          if (!candidateTarget || !resolveCompositionPlacement(structure.value, parsed, candidateTarget))
            continue;
          element = candidate;
          liveKey = candidateKey;
          break;
        }
      }
      const liveEntry = liveKey ? entries.value.get(liveKey) : undefined;
      const liveIsSource =
        (parsed?.kind === 'node' &&
          liveEntry?.nodeId === parsed.nodeId &&
          containerKey(liveEntry.container) === containerKey(parsed.container)) ||
        // A palette field is rendered only in the transient layout, and therefore has no entry in
        // the persisted structure map. Recognise its layout key explicitly so hovering its own
        // preview card remains neutral while every other live card can remain authoritative.
        (parsed?.kind === 'metadata' &&
          parsed.metadata.kind === 'field' &&
          liveKey?.endsWith(`:field:${parsed.metadata.fieldName ?? ''}`));
      // Form fields and groups share stable receivers throughout a held gesture, including
      // cross-group moves. Their receiver geometry does not change in the same axis as a list.
      const preferLiveTarget =
        parsed?.kind === 'node' &&
        !['form', 'group', 'groups'].includes(parsed.container.kind) &&
        Boolean(liveEntry) &&
        !liveIsSource;
      const frozen = listSlot || preferLiveTarget ? undefined : frozenTargetAt(x, y);
      const key = gridSlot?.key ?? listSlot?.key ?? (preferLiveTarget ? liveKey : (frozen?.[0] ?? liveKey));
      const entry = key && entries.value.get(key);
      if (!key || !entry) return;
      // Field renderer internals may repeat the same layout key on a label or control. Use the
      // largest live shell so a pointer crossing the input cannot turn a centre drop into “after”.
      const rect =
        gridSlot?.rect ??
        listSlot?.rect ??
        (entry.container.kind === 'list' ? listSlotRect(key, element) : undefined) ??
        frozen?.[1] ??
        largestLiveRect(key, element);
      if (!rect) return;
      // A grid cell's left/right halves are stable receiver zones. Cross-row ordering is selected
      // by the target cell itself, not by turning each cell into diagonal hit triangles.
      const horizontal = entry.axis === 'x' || entry.axis === 'grid';
      const coordinate = horizontal ? (x ?? rect.left + rect.width / 2) - rect.left : y - rect.top;
      const size = horizontal ? rect.width : rect.height;
      const ratio = coordinate / Math.max(size, 1);
      // The pointer naturally starts inside the source field. This is neither a candidate nor an
      // invalid drop: keeping it neutral avoids a misleading red rejection before the user moves.
      if (
        !gridSlot &&
        parsed?.kind === 'node' &&
        entry.nodeId === parsed.nodeId &&
        containerKey(entry.container) === containerKey(parsed.container)
      ) {
        // Reordering can move the dragged column under the pointer before mouseup. It is
        // still the displayed placement, not a cancellation or a new insertion into itself.
        const staged = transientPlacement.value;
        if (entry.container.kind === 'list' && staged && sameSource(staged.source, parsed)) {
          const anchor = [...entries.value].find(
            ([, candidate]) =>
              candidate.container.kind === 'list' && candidate.nodeId === staged.target.anchorId,
          );
          if (anchor)
            return {
              instanceId,
              kind: 'node',
              node: { key: anchor[0], title: anchor[1].title },
              position: staged.target.position,
            };
        }
        return;
      }
      // Edges explicitly mean before/after. The centre belongs to the field itself and uses the
      // movement direction, so entering B no longer feels like only B's latter half is a target.
      const positionFromPointer =
        ratio < 0.24 ? 'before' : ratio > 0.76 ? 'after' : preferredPosition(parsed, entry);
      const isNewMetadataField = parsed?.kind === 'metadata' && parsed.metadata.kind === 'field';
      const rawDropPosition =
        entry.inside &&
        parsed &&
        !(parsed.kind === 'node' && parsed.container.kind === 'groups') &&
        ratio >= 0.24 &&
        ratio <= 0.76
          ? 'inside'
          : !entry.nodeId
            ? 'inside'
            : // On first entry a palette field has no source position, so centre means before. Once the
              // temporary card is visible, use its staged position as a virtual source and let the user
              // drag naturally from slot 1 to slot 2 (or back) without aiming for a thin edge zone.
              isNewMetadataField
              ? ratio > 0.76
                ? 'after'
                : 'before'
              : entry.axis !== 'grid' && (position === 'before' || position === 'after')
                ? position
                : positionFromPointer;
      const dropPosition =
        rawDropPosition === 'inside' ||
        !parsed ||
        !isNewMetadataField ||
        position === 'before' ||
        position === 'after'
          ? rawDropPosition
          : stabilizeDirectionalPosition(key, parsed, rawDropPosition, coordinate, size);
      const target = gridSlot?.target ?? (parsed && targetOf(key, dropPosition, parsed));
      const resolved = parsed && target && resolveCompositionPlacement(structure.value, parsed, target);
      if (parsed && target && resolved) {
        capturePlacementGeometry();
        const next = {
          source: parsed,
          target,
          placement: resolved,
          title: source?.node.title ?? entry.title,
        };
        if (!samePlacement(transientPlacement.value, next)) transientPlacement.value = next;
      } else if (transientPlacement.value) {
        transientPlacement.value = undefined;
      }
      const inside = target?.position === 'inside';
      const staged = Boolean(resolved);
      const rootRect = root.value!.getBoundingClientRect();
      const width =
        !inside && horizontal ? (staged ? Math.min(Math.max(88, rect.width * 0.7), 180) : 3) : rect.width;
      const height =
        !inside && !horizontal ? (staged ? Math.min(Math.max(36, rect.height * 0.7), 52) : 3) : rect.height;
      indicator.value = {
        left:
          rect.left -
          rootRect.left +
          root.value!.scrollLeft +
          (!inside && horizontal ? (dropPosition === 'after' ? rect.width - width / 2 : -width / 2) : 0),
        top:
          rect.top -
          rootRect.top +
          root.value!.scrollTop +
          (!inside && !horizontal ? (dropPosition === 'after' ? rect.height - height / 2 : -height / 2) : 0),
        width,
        height,
      };
      return {
        instanceId,
        kind: 'node',
        node: {
          key: gridSlot
            ? ([...entries.value].find(
                ([, candidate]) =>
                  candidate.nodeId === target?.anchorId &&
                  containerKey(candidate.container) === containerKey(entry.container),
              )?.[0] ?? key)
            : key,
          title: entry.title,
        },
        position: gridSlot?.target.position ?? (inside ? 'inside' : dropPosition),
      };
    },
    allow: (event) => !!placement(event),
    operation: (source) => (source.instanceId === instanceId ? 'move' : 'copy'),
    drop: (event) => {
      const staged = transientPlacement.value;
      const source = sourceOf(event.source);
      // The hub's drop event may retain a prior before/after flag. Commit the exact candidate the
      // user saw immediately before release so the preview and persisted order cannot diverge.
      const result = staged && sameSource(staged.source, source) ? staged : placement(event);
      if (!result) return;
      // Hub teardown precedes drop delivery. Keep the staged order through that hand-off so the
      // committed draft takes over without briefly restoring and replaying the FLIP animation.
      committingPlacement = true;
      onDrop(result.source, result.target);
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
    {
      start: () => {
        capturePlacementGeometry();
        dragSourceElement?.classList.add('page-composer-drag-source');
      },
      end: () => {
        dragSourceElement?.classList.remove('page-composer-drag-source');
        dragSourceElement = undefined;
      },
    },
  );
  const groupOutline = shallowRef<Record<string, string>>();
  const columnOutline = shallowRef<Record<string, string>>();
  let outlineFrame = 0;
  let columnOutlineFrame = 0;
  function updateGroupOutline() {
    const entry = draggingKey.value && entries.value.get(draggingKey.value);
    if (!root.value || !entry || entry.container.kind !== 'groups') return;
    const group = { kind: 'group' as const, groupId: entry.nodeId! };
    const rectangles = [...entries.value].flatMap(([key, candidate]) => {
      if (key !== draggingKey.value && containerKey(candidate.container) !== containerKey(group)) return [];
      const rect = largestLiveRect(key, undefined);
      return rect && rect.width && rect.height ? [rect] : [];
    });
    if (rectangles.length) {
      const rootRect = root.value.getBoundingClientRect();
      const left = Math.min(...rectangles.map((rect) => rect.left));
      const top = Math.min(...rectangles.map((rect) => rect.top));
      const right = Math.max(...rectangles.map((rect) => rect.right));
      const bottom = Math.max(...rectangles.map((rect) => rect.bottom));
      const next = {
        left: `${left - rootRect.left + root.value.scrollLeft - 3}px`,
        top: `${top - rootRect.top + root.value.scrollTop - 3}px`,
        width: `${right - left + 6}px`,
        height: `${bottom - top + 6}px`,
      };
      if (Object.entries(next).some(([key, value]) => groupOutline.value?.[key] !== value))
        groupOutline.value = next;
    }
    outlineFrame = requestAnimationFrame(updateGroupOutline);
  }
  watch(
    draggingKey,
    () => {
      cancelAnimationFrame(outlineFrame);
      groupOutline.value = undefined;
      updateGroupOutline();
    },
    { flush: 'post' },
  );
  /**
   * A list column is rendered by two independent tables in the adapter: one for the header and
   * one for its rows.  The handle lives inside a header label, so a CSS outline on the source
   * only describes that label.  Draw one preview-owned outline from the real table cells instead.
   */
  function listColumnName(source: CompositionPlacementSource | undefined) {
    if (source?.kind === 'metadata' && source.metadata.kind === 'field') return source.metadata.fieldName;
    if (source?.kind !== 'node') return undefined;
    return [...entries.value]
      .find(
        ([, entry]) =>
          entry.axis === 'x' && entry.container.kind === 'list' && entry.nodeId === source.nodeId,
      )?.[0]
      ?.split(':')
      .at(-1);
  }
  function updateColumnOutline() {
    const source = draggingSource.value && sourceOf(draggingSource.value);
    const fieldName = listColumnName(source);
    if (!root.value || !fieldName) {
      columnOutline.value = undefined;
      return;
    }
    const keys = [`list:header:${fieldName}`, `list:field:${fieldName}`];
    const cells = new Set<HTMLElement>();
    for (const key of keys)
      for (const element of root.value.querySelectorAll<HTMLElement>(
        `[data-page-composition-layout-key="${CSS.escape(key)}"]`,
      )) {
        const cell = element.closest<HTMLElement>('th, td') ?? element;
        if (cell.getBoundingClientRect().width && cell.getBoundingClientRect().height) cells.add(cell);
      }
    const rectangles = [...cells]
      .map((cell) => visibleTableCellRect(cell, root.value!))
      .filter((rect): rect is DOMRect => rect != null);
    if (rectangles.length) {
      const rootRect = root.value.getBoundingClientRect();
      const left = Math.min(...rectangles.map((rect) => rect.left));
      const top = Math.min(...rectangles.map((rect) => rect.top));
      const right = Math.max(...rectangles.map((rect) => rect.right));
      const bottom = Math.max(...rectangles.map((rect) => rect.bottom));
      const next = {
        left: `${left - rootRect.left + root.value.scrollLeft - 2}px`,
        top: `${top - rootRect.top + root.value.scrollTop - 2}px`,
        width: `${right - left + 4}px`,
        height: `${bottom - top + 4}px`,
      };
      if (Object.entries(next).some(([key, value]) => columnOutline.value?.[key] !== value))
        columnOutline.value = next;
    } else columnOutline.value = undefined;
    columnOutlineFrame = requestAnimationFrame(updateColumnOutline);
  }
  /** Keep a scrolled-out table cell from extending the outline over nearby preview controls. */
  function visibleTableCellRect(cell: HTMLElement, boundary: HTMLElement) {
    let { left, top, right, bottom } = cell.getBoundingClientRect();
    for (let parent = cell.parentElement; parent && parent !== boundary; parent = parent.parentElement) {
      const style = getComputedStyle(parent);
      if (!/(auto|scroll|hidden|clip)/.test(`${style.overflowX} ${style.overflowY}`)) continue;
      const clip = parent.getBoundingClientRect();
      left = Math.max(left, clip.left);
      top = Math.max(top, clip.top);
      right = Math.min(right, clip.right);
      bottom = Math.min(bottom, clip.bottom);
      if (right <= left || bottom <= top) return undefined;
    }
    return new DOMRect(left, top, right - left, bottom - top);
  }
  watch(
    [draggingKey, draggingSource],
    () => {
      cancelAnimationFrame(columnOutlineFrame);
      columnOutline.value = undefined;
      updateColumnOutline();
    },
    { flush: 'post' },
  );
  onBeforeUnmount(() => {
    cancelAnimationFrame(outlineFrame);
    cancelAnimationFrame(columnOutlineFrame);
  });
  let dragSourceElement: HTMLElement | undefined;
  watch([enabled, hovered, draggingSource], ([isEnabled, target, activeSource]) => {
    const parsed = activeSource && sourceOf(activeSource);
    const activePlacementDrag =
      parsed?.kind === 'metadata' ? parsed.metadata.kind === 'field' : parsed?.kind === 'node';
    if (!isEnabled || !target) {
      if (!committingPlacement && (!isEnabled || !activePlacementDrag)) {
        transientPlacement.value = undefined;
        if (!target) clearPlacementGeometry();
      }
    }
    if (!isEnabled) clear();
  });
  // A preview placement persists through an async descriptor refresh. Clearing it on the next
  // tick briefly restores the old order, then makes the refreshed descriptor animate again.
  // The new structure is the acknowledgement that the committed draft now owns this order.
  watch(
    structure,
    () => {
      if (!committingPlacement) return;
      committingPlacement = false;
      transientPlacement.value = undefined;
      clearPlacementGeometry();
    },
    { flush: 'post' },
  );
  function abandonPendingPlacement() {
    committingPlacement = false;
    transientPlacement.value = undefined;
    indicator.value = undefined;
    clearPlacementGeometry();
    clear();
  }
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
        dragSourceElement =
          (event.currentTarget as HTMLElement | null)?.closest<HTMLElement>(
            '[data-page-composition-layout-key], [data-composer-target]',
          ) ?? undefined;
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
    groupOutline,
    columnOutline,
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
    transientPlacement: computed(() => transientPlacement.value),
    abandonPendingPlacement,
  };
}
