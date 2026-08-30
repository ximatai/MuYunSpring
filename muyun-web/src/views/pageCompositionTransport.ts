/** Stable wire codes of the page-composition domain's CodeTitleEnum values. */
export const pageCompositionTransport = {
  managementContract: 'management',
  webClient: 'web',
  globalScope: 'global',
  draftRevision: 'draft',
  publishedRevision: 'published',
  previewRevisionPath(variantId: string, revisionId: string) {
    return `/platform.presentation-variant/${encodeURIComponent(variantId)}/revisions/${encodeURIComponent(revisionId)}/preview`;
  },
} as const;
