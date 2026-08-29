import { describe, expect, it } from 'vitest';
import { pageCompositionTransport } from '../../src/views/pageCompositionTransport';

describe('pageCompositionTransport', () => {
  it('uses CodeTitleEnum codes instead of Java enum names', () => {
    expect(pageCompositionTransport).toEqual({
      managementContract: 'management',
      webClient: 'web',
      globalScope: 'global',
      draftRevision: 'draft',
      publishedRevision: 'published',
    });
  });
});
