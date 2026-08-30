import { describe, expect, it } from 'vitest';
import { pageCompositionTransport } from '../../src/views/pageCompositionTransport';

describe('pageCompositionTransport', () => {
  it('uses CodeTitleEnum codes instead of Java enum names', () => {
    expect(pageCompositionTransport.managementContract).toBe('management');
    expect(pageCompositionTransport.webClient).toBe('web');
    expect(pageCompositionTransport.globalScope).toBe('global');
    expect(pageCompositionTransport.draftRevision).toBe('draft');
    expect(pageCompositionTransport.publishedRevision).toBe('published');
  });

  it('builds the scoped preview endpoint from opaque identifiers', () => {
    expect(pageCompositionTransport.previewRevisionPath('variant/a', 'revision b')).toBe(
      '/platform.presentation-variant/variant%2Fa/revisions/revision%20b/preview',
    );
  });
});
