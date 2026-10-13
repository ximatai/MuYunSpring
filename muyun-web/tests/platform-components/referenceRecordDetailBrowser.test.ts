import { expect, it } from 'vitest';
import { flushPromises } from '@vue/test-utils';
import { createLanguageContext, type HttpClient } from '@/web-core';
import { createReferenceRecordDetailBrowser } from '@/platform-components/referenceRecordDetailBrowser';

it('updates reference detail labels in its own language while preserving record values', async () => {
  const language = createLanguageContext();
  const http = {
    request: async ({ path }: { path: string }) =>
      path.endsWith('context')
        ? {
            moduleAlias: 'demo.target',
            capabilities: [],
            actions: [],
            uiDescriptor: {
              moduleAlias: 'demo.target',
              defaultEditor: {
                viewKind: 'FORM',
                fields: [{ fieldRef: { fieldName: 'title' }, label: '名称' }],
              },
            },
            textBindings: {
              '/uiDescriptor/defaultEditor/fields/0/label': {
                source: {
                  sourceKind: 'CODE',
                  sourceId: 'target.title',
                  sourceVersion: 'v1',
                  originalText: '名称',
                },
                languages: {
                  en: {
                    text: 'Name',
                    requestedTag: 'en',
                    resourceTag: 'en',
                    missing: false,
                    fallback: false,
                  },
                },
              },
            },
          }
        : { id: 'record-1', title: '原始名称' },
  } as HttpClient;
  const browser = createReferenceRecordDetailBrowser(http, language);
  browser.open('demo.target', 'record-1');
  await flushPromises();
  expect(browser.fields.value.get('title')?.label).toBe('名称');
  language.setLanguage('en');
  expect(browser.fields.value.get('title')?.label).toBe('Name');
  expect(browser.active.value?.record?.title).toBe('原始名称');
  expect(browser.activeContext.value?.runtime.snapshot()?.uiDescriptor?.defaultEditor?.fields[0]?.label).toBe(
    '名称',
  );
});
