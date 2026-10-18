import { computed } from 'vue';
import { expect, it, vi } from 'vitest';
import { createLanguageContext } from '@/web-core/language';
import { businessContentLanguageContext } from '@/web-core/businessContentLanguage';
import {
  recordBusinessContentArray,
  recordPresentationLabel,
  withoutRecordPresentation,
} from '@/web-core/presentationTexts';
import {
  referencePickerDisplayTitle,
  referencePickerSummary,
} from '@/platform-components/referencePickerModel';
import { createSourceReferencePickerProvider } from '@/platform-components/sourceReferencePickerProvider';
import {
  createModuleTreeClient,
  createNavigatorReferenceCrudClient,
  normalizeModuleRecord,
} from '@/web-core/module/staticModuleClient';
import { createReferenceResolveClient } from '@/web-core/referenceResolveClient';
import type { HttpClient, HttpRequestOptions } from '@/web-core/http';

const text = (originalText: string, english: string, german: string) => ({
  originalText,
  readings: { en: english, de: german },
});

it('switches only business labels, preserves raw identity and invalidates an edited original', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const content = businessContentLanguageContext(language);
  const record = { id: 'one', title: '设备', $contentTexts: { title: text('设备', 'Equipment', 'Gerät') } };
  const display = computed(() => recordPresentationLabel(record, 'title', language));
  expect(display.value).toBe('Equipment');
  content.setLanguage('de');
  expect(display.value).toBe('Gerät');
  expect(language.languageTag.value).toBe('en');
  expect(record.title).toBe('设备');
  expect(recordPresentationLabel({ ...record, title: '原文已修改' }, 'title', language)).toBe('原文已修改');
  expect(withoutRecordPresentation(record)).toEqual({ id: 'one', title: '设备' });
});

it('keeps same-name ordered reference labels independent and never translates arbitrary nested business JSON', () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const record = {
    names: ['同名', '同名'],
    businessJson: { $contentTexts: { title: 'business fact' } },
    $contentTexts: {
      names: [text('同名', 'Equipment B', 'Gerät B'), text('同名', 'Equipment A', 'Gerät A')],
    },
  };
  expect(recordBusinessContentArray(record, 'names', language)).toEqual(['Equipment B', 'Equipment A']);
  expect(record.names).toEqual(['同名', '同名']);
  expect(recordBusinessContentArray({ ...record, names: ['新名', '同名'] }, 'names', language)).toEqual([
    '新名',
    'Equipment A',
  ]);
  expect(withoutRecordPresentation(record).businessJson).toBe(record.businessJson);
});

it('carries title and context snapshots through source selection without modifying projections or affect patches', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const item = {
    id: 'category-a',
    title: ' 同名 ',
    subtitle: '分类',
    titleContent: text(' 同名 ', 'Equipment A', 'Gerät A'),
    subtitleContent: text('分类', 'Category', 'Kategorie'),
    projections: { title: ' 同名 ' },
    affectPatch: { snapshot: ' 同名 ' },
  };
  const resolve = vi.fn().mockResolvedValue({ options: [item], total: 1 });
  const source = createSourceReferencePickerProvider({
    sourceModuleAlias: 'test.product',
    fieldName: 'categoryId',
    reference: { targetModuleAlias: 'test.category' } as never,
    resolver: () => ({ resolve }),
    formValues: () => ({}),
    source: () => undefined,
  });
  const response = await source.searchPage({
    keyword: 'Equipment',
    pageNum: 1,
    pageSize: 20,
    scope: { selections: [] },
  });
  const candidate = response.records[0]!;
  expect(referencePickerDisplayTitle(referencePickerSummary(candidate), language)).toBe(
    'Equipment A · Category',
  );
  businessContentLanguageContext(language).setLanguage('de');
  expect(referencePickerDisplayTitle(candidate, language)).toBe('Gerät A · Kategorie');
  expect(resolve).toHaveBeenCalledTimes(1);
  expect(candidate.title).toBe(' 同名 ');
  expect(candidate.projections).toBe(item.projections);
  expect(candidate.affectPatch).toBe(item.affectPatch);
});

it('requests bounded reading snapshots on shared read transports and strips them from ordinary writes', async () => {
  const requests: HttpRequestOptions[] = [];
  const http: HttpClient = {
    request: async (options) => {
      requests.push(options);
      return { records: [], results: [], options: [], children: [], id: 'one', title: '设备' } as never;
    },
  };
  const client = createModuleTreeClient(http, { moduleAlias: 'test.category' });
  await client.query(undefined, { contentLanguage: 'de' });
  await client.view('one');
  await client.tree();
  await client.treeFlat();
  await client.subtree('one');
  await createNavigatorReferenceCrudClient(http, { moduleAlias: 'test.category' }).translate(['one']);
  await createReferenceResolveClient(http, 'test.product').resolve('categoryId', { mode: 'QUERY' });
  expect(requests).toHaveLength(7);
  for (const request of requests) expect(request.headers?.['X-MuYun-Content-Languages']).toBe('zh-CN,en,de');
  expect(requests[0]!.headers?.['X-MuYun-Content-Language']).toBe('de');
  const record = { id: 'one', title: '设备', $contentTexts: { title: text('设备', 'Equipment', 'Gerät') } };
  await client.update('one', record);
  expect(requests.at(-1)?.body).toEqual({ id: 'one', title: '设备' });
  expect(requests.at(-1)?.headers).toBeUndefined();
  expect(
    normalizeModuleRecord({
      id: 'one',
      version: 0,
      values: { title: '设备' },
      children: {},
      $contentTexts: record.$contentTexts,
    }),
  ).toEqual({ id: 'one', version: 0, title: '设备', $contentTexts: record.$contentTexts });
});
