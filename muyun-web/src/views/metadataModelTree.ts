import type { Metadata, MetadataField, ModuleMetadataRelation } from '@muyun/web-contracts';
import type { UiTreeDropEvent, UiTreeNode } from '@muyun/vue-ui-antdv';

export type MetadataModelTreeNodeKind = 'METADATA' | 'FIELD';

export interface MetadataModelTreeNode extends UiTreeNode {
  modelKind: MetadataModelTreeNodeKind;
  relationId?: string;
  fieldId?: string;
  /** Parent relation, not the visual root. It makes the reorder boundary explicit. */
  parentRelationId?: string;
  /** Locked nodes are intentionally visible but never draggable. */
  draggable?: boolean;
}

export interface MetadataModelTreeInput {
  relations: ModuleMetadataRelation[];
  metadataById: Record<string, Metadata>;
  fieldsByRelation: Record<string, MetadataField[]>;
  fieldLocked: (relation: ModuleMetadataRelation, field: MetadataField) => boolean;
}

/**
 * The governance tree is a projection of the model, not a second persistence shape. Metadata
 * branches and field leaves retain their relation/field identities so the page can route to
 * strongly typed cards. The explorer title already states "数据模型", so entity nodes are roots
 * rather than being wrapped in a redundant virtual node.
 */
export function buildMetadataModelTree(input: MetadataModelTreeInput): MetadataModelTreeNode[] {
  const relationByMetadataId = new Map(
    input.relations
      .filter((relation): relation is ModuleMetadataRelation & { id: string; metadataId: string } =>
        Boolean(relation.id && relation.metadataId),
      )
      .map((relation) => [relation.metadataId, relation]),
  );
  const childrenByParentMetadataId = new Map<string | undefined, ModuleMetadataRelation[]>();
  for (const relation of input.relations) {
    if (!relation.id) continue;
    const parentMetadataId = relation.parentMetadataId || undefined;
    const siblings = childrenByParentMetadataId.get(parentMetadataId) ?? [];
    siblings.push(relation);
    childrenByParentMetadataId.set(parentMetadataId, siblings);
  }
  for (const siblings of childrenByParentMetadataId.values()) {
    siblings.sort((left, right) => (left.sortOrder ?? 0) - (right.sortOrder ?? 0));
  }

  const visiting = new Set<string>();
  function relationNode(relation: ModuleMetadataRelation): MetadataModelTreeNode | undefined {
    if (!relation.id || visiting.has(relation.id)) return undefined;
    visiting.add(relation.id);
    const metadata = relation.metadataId ? input.metadataById[relation.metadataId] : undefined;
    const childRelations = relation.metadataId
      ? (childrenByParentMetadataId.get(relation.metadataId) ?? [])
      : [];
    const fields = [...(input.fieldsByRelation[relation.id] ?? [])].sort(
      (left, right) => (left.sortOrder ?? 0) - (right.sortOrder ?? 0),
    );
    const children: MetadataModelTreeNode[] = [
      ...fields.map((field) => fieldNode(relation, field)),
      ...childRelations.flatMap((child) => {
        const node = relationNode(child);
        return node ? [node] : [];
      }),
    ];
    visiting.delete(relation.id);
    return {
      key: metadataNodeKey(relation.id),
      title: metadata?.title || relation.relationAlias || relation.metadataId || '未命名实体',
      secondary: metadata?.alias || relation.relationAlias,
      tag: normalizedRole(relation.relationRole) === 'MAIN' ? '主实体' : '子实体',
      isLeaf: false,
      modelKind: 'METADATA',
      relationId: relation.id,
      parentRelationId: relation.parentMetadataId
        ? relationByMetadataId.get(relation.parentMetadataId)?.id
        : undefined,
      draggable: true,
      children,
    };
  }

  function fieldNode(relation: ModuleMetadataRelation, field: MetadataField): MetadataModelTreeNode {
    const fieldId = field.id ?? field.fieldName ?? 'unknown';
    const locked = input.fieldLocked(relation, field);
    return {
      key: fieldNodeKey(relation.id!, fieldId),
      title: field.title || field.fieldName || '未命名字段',
      secondary: field.fieldName,
      tag: locked ? '受保护' : undefined,
      muted: locked,
      isLeaf: true,
      modelKind: 'FIELD',
      relationId: relation.id,
      fieldId,
      parentRelationId: relation.id,
      draggable: !locked,
    };
  }

  const rootRelations = (childrenByParentMetadataId.get(undefined) ?? []).flatMap((relation) => {
    const node = relationNode(relation);
    return node ? [node] : [];
  });
  return rootRelations;
}

export function metadataNodeKey(relationId: string) {
  return `metadata-model:relation:${relationId}`;
}

export function fieldNodeKey(relationId: string, fieldId: string) {
  return `metadata-model:field:${relationId}:${fieldId}`;
}

export function parseMetadataModelTreeKey(
  key: string,
):
  | { kind: 'METADATA'; relationId: string }
  | { kind: 'FIELD'; relationId: string; fieldId: string }
  | undefined {
  const relation = /^metadata-model:relation:(.+)$/.exec(key);
  if (relation) return { kind: 'METADATA', relationId: relation[1] };
  const field = /^metadata-model:field:(.+):(.+)$/.exec(key);
  return field ? { kind: 'FIELD', relationId: field[1], fieldId: field[2] } : undefined;
}

/** Only same-parent gap drops are legal. Dropping "on" a node would imply changing model structure. */
export function canReorderMetadataModelTree(
  event: Pick<UiTreeDropEvent, 'dragNode' | 'dropNode' | 'dropToGap'>,
): boolean {
  const drag = event.dragNode as MetadataModelTreeNode;
  const drop = event.dropNode as MetadataModelTreeNode;
  return Boolean(
    event.dropToGap &&
    drag.draggable &&
    drop.draggable &&
    drag.modelKind === drop.modelKind &&
    drag.parentRelationId === drop.parentRelationId,
  );
}

export function reorderedIds<T extends { id?: string }>(
  items: T[],
  sourceId: string,
  targetId: string,
  place: -1 | 1,
): string[] {
  const ids = items.map((item) => item.id).filter((id): id is string => Boolean(id));
  const sourceIndex = ids.indexOf(sourceId);
  const targetIndex = ids.indexOf(targetId);
  if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) return ids;
  const [source] = ids.splice(sourceIndex, 1);
  const adjustedTarget = ids.indexOf(targetId);
  ids.splice(place < 0 ? adjustedTarget : adjustedTarget + 1, 0, source);
  return ids;
}

function normalizedRole(role: string | undefined) {
  return role?.trim().toUpperCase();
}
