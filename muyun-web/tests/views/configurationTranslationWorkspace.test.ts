import { expect, it, vi } from 'vitest';
import type { HttpClient } from '@/web-core';
import { createConfigurationTranslationWorkspace } from '@/views/configurationTranslationWorkspace';

it('keeps target-language drafts separate and sends source and optimistic versions for independent publication', async () => {
  const source = {
    sourceKind: 'CONFIG',
    sourceId: 'config/ui/test.order/title',
    sourceVersion: 'original-1',
    originalText: '订单',
  };
  const request = vi.fn(async ({ path, body }: { path: string; body?: Record<string, unknown> }) => {
    if (path.endsWith('/sources')) return [source];
    if (path.endsWith('/translations')) return [];
    return {
      data: {
        id: 'translation-1',
        version: path.endsWith('/publish') ? 2 : 1,
        moduleAlias: 'test.order',
        sourceId: source.sourceId,
        sourceVersion: source.sourceVersion,
        originalText: source.originalText,
        languageTag: 'en',
        draftText: body?.text ?? 'Order',
        publishedText: path.endsWith('/publish') ? 'Order' : undefined,
        publishedRevision: path.endsWith('/publish') ? 1 : 0,
      },
    };
  });
  const workspace = createConfigurationTranslationWorkspace({ request } as unknown as HttpClient);
  workspace.moduleAlias.value = 'test.order';
  await workspace.load();
  workspace.text.value = 'Order';
  workspace.targetLanguage.value = 'zh-CN';
  workspace.text.value = '订单显示标签';
  workspace.targetLanguage.value = 'en';
  expect(workspace.text.value).toBe('Order');
  await workspace.save();
  expect(request).toHaveBeenLastCalledWith(
    expect.objectContaining({
      path: '/platform.localization/drafts',
      body: {
        moduleAlias: 'test.order',
        sourceId: source.sourceId,
        sourceVersion: 'original-1',
        languageTag: 'en',
        text: 'Order',
        version: undefined,
      },
    }),
  );
  expect(workspace.source.value?.originalText).toBe('订单');
  expect(workspace.translation.value?.publishedText).toBeUndefined();
  await workspace.publish();
  expect(request).toHaveBeenLastCalledWith({
    method: 'POST',
    path: '/platform.localization/translations/translation-1/publish',
    body: { sourceVersion: 'original-1', version: 1 },
  });
  expect(workspace.translation.value?.publishedText).toBe('Order');
  workspace.text.value = 'Sales order';
  const count = request.mock.calls.length;
  await workspace.publish();
  expect(request).toHaveBeenCalledTimes(count);
  workspace.moduleAlias.value = 'test.other';
  await workspace.save();
  expect(request).toHaveBeenCalledTimes(count);
});

it('keeps stale translations as review references without automatically publishing them for a new original', async () => {
  const workspace = createConfigurationTranslationWorkspace({
    request: async ({ path }: { path: string }) =>
      path.endsWith('/sources')
        ? [{ sourceKind: 'CONFIG', sourceId: 'source', sourceVersion: 'v2', originalText: '订单名称' }]
        : [
            {
              id: 'old',
              sourceId: 'source',
              sourceVersion: 'v1',
              languageTag: 'en',
              draftText: 'Order',
              publishedText: 'Order',
            },
          ],
  } as unknown as HttpClient);
  workspace.moduleAlias.value = 'test.order';
  await workspace.load();
  expect(workspace.translation.value).toBeUndefined();
  expect(workspace.previous.value?.publishedText).toBe('Order');
  expect(workspace.text.value).toBe('');
});

it('reloads saved text from the server while preserving unsaved text with its original optimistic version', async () => {
  const source = { sourceKind: 'CONFIG', sourceId: 'source', sourceVersion: 'v1', originalText: '订单' };
  let row = {
    id: 'one',
    version: 1,
    sourceId: 'source',
    sourceVersion: 'v1',
    languageTag: 'en',
    draftText: 'Order',
  };
  const request = vi.fn(async ({ path, body }: { path: string; body?: Record<string, unknown> }) => {
    if (path.endsWith('/sources')) return [source];
    if (path.endsWith('/translations')) return [{ ...row }];
    row = { ...row, version: row.version + 1, draftText: String(body?.text) };
    return { data: { ...row } };
  });
  const workspace = createConfigurationTranslationWorkspace({ request } as unknown as HttpClient);
  workspace.moduleAlias.value = 'test.order';
  await workspace.load();
  workspace.text.value = 'Saved local';
  await workspace.save();
  expect(workspace.dirty.value).toBe(false);
  row = { ...row, version: 3, draftText: 'Other reviewer update' };
  await workspace.load();
  expect(workspace.text.value).toBe('Other reviewer update');
  expect(workspace.dirty.value).toBe(false);
  workspace.text.value = 'Unsaved local';
  row = { ...row, version: 4, draftText: 'Concurrent change' };
  await workspace.load();
  expect(workspace.text.value).toBe('Unsaved local');
  await workspace.save();
  expect(request).toHaveBeenLastCalledWith(
    expect.objectContaining({ body: expect.objectContaining({ version: 3 }) }),
  );
});
