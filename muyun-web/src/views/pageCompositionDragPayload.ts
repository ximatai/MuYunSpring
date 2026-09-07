export const PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE = 'application/x-muyun-page-composer';

export type MetadataDragPayload =
  | {
      kind: 'field';
      fieldId: string;
      /** Ephemeral preview facts; placement persistence still resolves by fieldId only. */
      fieldName?: string;
      title?: string;
      fieldSpecAlias?: string;
      required?: boolean;
    }
  | { kind: 'relation'; relationId: string }
  | { kind: 'relationField'; relationId: string; fieldId: string };

/** A module action is a source fact, distinct from metadata fields and relations. */
export type ModuleActionDragPayload = { kind: 'action'; actionCode: string };
export type PageCompositionDragPayload = MetadataDragPayload | ModuleActionDragPayload;

export function parseMetadataDragPayload(payload: unknown): MetadataDragPayload | undefined {
  if (!payload || typeof payload !== 'object') return undefined;
  const candidate = payload as Record<string, unknown>;
  if (candidate.kind === 'field' && nonEmptyString(candidate.fieldId)) {
    return {
      kind: 'field',
      fieldId: candidate.fieldId,
      ...(nonEmptyString(candidate.fieldName) ? { fieldName: candidate.fieldName } : {}),
      ...(nonEmptyString(candidate.title) ? { title: candidate.title } : {}),
      ...(nonEmptyString(candidate.fieldSpecAlias) ? { fieldSpecAlias: candidate.fieldSpecAlias } : {}),
      ...(typeof candidate.required === 'boolean' ? { required: candidate.required } : {}),
    };
  }
  if (candidate.kind === 'relation' && nonEmptyString(candidate.relationId)) {
    return { kind: 'relation', relationId: candidate.relationId };
  }
  if (
    candidate.kind === 'relationField' &&
    nonEmptyString(candidate.relationId) &&
    nonEmptyString(candidate.fieldId)
  ) {
    return {
      kind: 'relationField',
      relationId: candidate.relationId,
      fieldId: candidate.fieldId,
    };
  }
  return undefined;
}

export function parsePageCompositionDragPayload(payload: unknown): PageCompositionDragPayload | undefined {
  const metadata = parseMetadataDragPayload(payload);
  if (metadata) return metadata;
  if (!payload || typeof payload !== 'object') return undefined;
  const candidate = payload as Record<string, unknown>;
  return candidate.kind === 'action' && nonEmptyString(candidate.actionCode)
    ? { kind: 'action', actionCode: candidate.actionCode }
    : undefined;
}

function nonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}
