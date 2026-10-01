import { describe, expect, it, vi } from 'vitest';
import { createPageCompositionPublicationCommand } from '@/views/pageCompositionPublication';

describe('page composition publication recovery', () => {
  it.each([true, false])(
    'uses the server commit receipt for a renamed composition: %s',
    async (committed) => {
      const request = vi.fn().mockResolvedValue(committed);
      const readRevisions = vi
        .fn()
        .mockResolvedValue([
          { id: 'revision-1', status: 'published', uiTreeJson: '{"fields":["customerName"]}' },
        ]);
      const revision = { id: 'revision-1', version: 2, uiTreeJson: '{"fields":["fieldtemporary"]}' };
      const composition = {
        relationId: 'main',
        newFields: [{ key: 'temporary', suggestedName: 'customerName' }],
      };
      const command = createPageCompositionPublicationCommand({
        http: { request },
        variantId: 'variant-1',
        revision,
        composition,
        previewBody: {},
        requireCurrent() {},
        readRevisions,
      });
      // Further editor changes cannot alter the submission being checked.
      revision.uiTreeJson = 'changed';
      composition.newFields[0].suggestedName = 'changed';
      const result = await command.lookup();
      expect(Boolean(result)).toBe(committed);
      expect(request).toHaveBeenCalledExactlyOnceWith({
        method: 'POST',
        path: '/platform.presentation_publish/revisions/revision-1/composition-result',
        body: {
          relationId: 'main',
          newFields: [{ key: 'temporary', suggestedName: 'customerName' }],
          revision: { id: 'revision-1', version: 2, uiTreeJson: '{"fields":["fieldtemporary"]}' },
        },
      });
      expect(readRevisions).not.toHaveBeenCalled();
    },
  );
});
