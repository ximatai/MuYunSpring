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
    const payload = JSON.parse(raw) as MetadataDragPayload;
    if (payload.kind === 'field' && payload.fieldId) return payload;
    if (payload.kind === 'relation' && payload.relationId) return payload;
    if (payload.kind === 'relationField' && payload.relationId && payload.fieldId) return payload;
  } catch {
    // Ignore native drops whose text payload is not created by this composer.
  }
  return undefined;
}
