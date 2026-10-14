import { createLanguageContext } from '@muyun/web-core';
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

it('renders publication receipts in the owning language without changing the reviewed body or content digest', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const request = vi.fn().mockResolvedValue(1);
  const revision = { id: 'revision-1', version: 2, uiTreeJson: '{"nodes":[{"title":"业务原文"}]}' };
  const original = structuredClone(revision);
  const command = createPageCompositionPublicationCommand({
    http: { request },
    language,
    variantId: 'variant-1',
    revision,
    previewBody: { revision: original },
    requireCurrent: vi.fn(),
    readRevisions: vi.fn(async () => [{ ...original, status: 'published' as const }]),
  });
  const reference = await command.receiptReference();
  const receipt = await command.execute();
  expect(receipt.receipt.title).toBe('Page saved and activated');
  expect(receipt.receipt.lines).toEqual(['The business page will use this confirmed configuration.']);
  language.setLanguage('zh-CN');
  expect(await command.receiptReference()).toEqual(reference);
  expect(await command.lookup()).toMatchObject({ title: '页面已保存并生效' });
  expect(revision).toEqual(original);
  expect(request).toHaveBeenCalledTimes(2);
  expect(request.mock.calls[1]).toMatchObject([{ body: original }]);
});
