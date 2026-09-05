export const PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE = 'application/x-muyun-page-composer';

export type MetadataDragPayload =
  | { kind: 'field'; fieldId: string }
  | { kind: 'relation'; relationId: string }
  | { kind: 'relationField'; relationId: string; fieldId: string };

export function parseMetadataDragPayload(payload: unknown): MetadataDragPayload | undefined {
  if (!payload || typeof payload !== 'object') return undefined;
  const candidate = payload as Record<string, unknown>;
  if (candidate.kind === 'field' && nonEmptyString(candidate.fieldId)) {
    return { kind: 'field', fieldId: candidate.fieldId };
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

function nonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}
