import { orderedFormItems, type PageComposerFormItem } from './pageCompositionDraftState';
import type { PageComposerField, PageComposerGroup, PageComposerRelation } from './pageCompositionDraftState';
import type { MetadataDragPayload } from './pageCompositionDragPayload';

/** Editor placement facts shared by the structure tree and the descriptor preview. */
export interface PageCompositionStructure {
  order?: PageComposerFormItem[];
  list: PageComposerField[];
  form: PageComposerField[];
  groups: PageComposerGroup[];
  relations: PageComposerRelation[];
}
export type CompositionContainer =
  | { kind: 'list' }
  | { kind: 'form' }
  | { kind: 'groups' }
  | { kind: 'relations' }
  | { kind: 'group'; groupId: string }
  | { kind: 'relation'; relationId: string };
export interface CompositionPlacementTarget {
  container: CompositionContainer;
  anchorId?: string;
  position: 'before' | 'after' | 'inside';
}
export type CompositionPlacementSource =
  | { kind: 'metadata'; metadata: MetadataDragPayload }
  | { kind: 'node'; container: CompositionContainer; nodeId: string };
export interface CompositionPlacement {
  container: CompositionContainer;
  index: number;
}
export const PAGE_COMPOSITION_NODE_DRAG_TYPE = 'application/x-muyun-page-composer-node';

export function containerKey(container: CompositionContainer): string {
  return container.kind === 'group'
    ? `group:${container.groupId}`
    : container.kind === 'relation'
      ? `relation:${container.relationId}`
      : container.kind;
}
export function compositionItems(structure: PageCompositionStructure, container: CompositionContainer) {
  switch (container.kind) {
    case 'list':
      return structure.list;
    case 'form':
      return orderedFormItems(structure.form, structure.groups, structure.order).map((item) =>
        item.kind === 'field'
          ? structure.form.find((field) => field.id === item.id)!
          : structure.groups.find((group) => group.id === item.id)!,
      );
    case 'groups':
      return structure.groups;
    case 'relations':
      return structure.relations;
    case 'group':
      return structure.groups.find((group) => group.id === container.groupId)?.fields;
    case 'relation':
      return structure.relations.find((relation) => relation.id === container.relationId)?.fields;
  }
}

/** Resolve against current facts at both hover and release; indexes refer to the list after removal. */
export function resolveCompositionPlacement(
  structure: PageCompositionStructure,
  source: CompositionPlacementSource,
  target: CompositionPlacementTarget,
): CompositionPlacement | undefined {
  const items = compositionItems(structure, target.container);
  if (!items || (target.anchorId && !items.some((item) => item.id === target.anchorId))) return;
  const fieldTarget = ['form', 'group'].includes(target.container.kind);
  let movingId: string | undefined;
  if (source.kind === 'metadata') {
    const metadata = source.metadata;
    if (metadata.kind === 'field') {
      if (target.container.kind !== 'list' && !fieldTarget) return;
      movingId = metadata.fieldId;
    } else if (metadata.kind === 'relation') {
      if (target.container.kind !== 'relations') return;
      movingId = metadata.relationId;
    } else {
      if (target.container.kind === 'relation' && target.container.relationId === metadata.relationId)
        movingId = metadata.fieldId;
      else if (target.container.kind === 'relations') movingId = metadata.relationId;
      else return;
    }
  } else {
    if (!compositionItems(structure, source.container)?.some((item) => item.id === source.nodeId)) return;
    const same = containerKey(source.container) === containerKey(target.container);
    const movingFormField = ['form', 'group'].includes(source.container.kind) && fieldTarget;
    const movingGroup = source.container.kind === 'groups' && target.container.kind === 'form';
    if (!same && !movingFormField && !movingGroup) return;
    movingId = source.nodeId;
  }
  if (movingId === target.anchorId) return;
  const remaining = items.filter((item) => item.id !== movingId);
  const anchor = target.anchorId
    ? remaining.findIndex((item) => item.id === target.anchorId)
    : remaining.length;
  const index =
    target.position === 'inside' ? remaining.length : anchor + (target.position === 'after' ? 1 : 0);
  return { container: target.container, index: Math.min(index, remaining.length) };
}
