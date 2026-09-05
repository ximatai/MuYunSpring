export const PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE = 'application/x-muyun-page-composer';

export type MetadataDragPayload =
  | { kind: 'field'; fieldId: string }
  | { kind: 'relation'; relationId: string }
  | { kind: 'relationField'; relationId: string; fieldId: string };

export function isPageCompositionDrag(dataTransfer?: DataTransfer | null) {
  return Array.from(dataTransfer?.types ?? []).includes(PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE);
}

export function parseMetadataDragPayload(
  dataTransfer?: DataTransfer | null,
): MetadataDragPayload | undefined {
  const raw =
    dataTransfer?.getData(PAGE_COMPOSITION_DRAG_PAYLOAD_TYPE) || dataTransfer?.getData('text/plain');
  if (!raw) return undefined;
  try {
    const payload = JSON.parse(raw) as unknown;
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
  } catch {
    // Ignore native drops whose text payload is not created by this composer.
  }
  return undefined;
}

function nonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}
