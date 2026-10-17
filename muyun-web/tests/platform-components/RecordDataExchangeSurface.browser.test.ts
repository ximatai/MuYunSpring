import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import RecordDataExchangeSurface from '@/platform-components/RecordDataExchangeSurface.vue';
import { createLanguageContext, provideLanguageContext, type ModuleContext } from '@muyun/web-core';
import '@/styles.css';
import 'ant-design-vue/dist/reset.css';

it('keeps review and import actions reachable while a long preview scrolls, including narrow screens', async () => {
  await page.viewport(1063, 804);
  const parsed = {
    documentId: 'one',
    previewDigest: 'one',
    purpose: 'DATA',
    language: 'en',
    sheets: [
      {
        entityAlias: 'product',
        sheetName: 'Products',
        main: true,
        rowCount: 100,
        matchFields: ['code'],
        fieldTitles: { code: 'Code', description: 'Description' },
        recommendedMatchField: 'code',
      },
    ],
    rows: Array.from({ length: 100 }, (_, index) => ({
      row: `row-${index}`,
      entity: 'product',
      relation: `P-${index}`,
      operation: 'CREATE',
      currentValues: {},
      changes: { description: `Business description ${index}` },
      languages: {},
      choices: [],
    })),
  };
  const context = {
    moduleAlias: 'test.product',
    http: { request: vi.fn().mockResolvedValue(parsed) },
    can: () => true,
    runtime: { snapshot: () => ({ capabilities: ['EXCHANGE'] }) },
  } as unknown as ModuleContext<unknown>;
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(createLanguageContext({ languageTag: 'en' }));
        return () => h(RecordDataExchangeSurface, { context, scopeKey: 'one' });
      },
    }),
    { attachTo: document.body },
  );
  try {
    await page.getByRole('button', { name: 'Data files', exact: true }).click();
    await page.getByRole('button', { name: 'Import data', exact: true }).click();
    const input = document.querySelector('input[type=file]') as HTMLInputElement;
    Object.defineProperty(input, 'files', {
      configurable: true,
      value: [new File(['data'], 'products.xlsx')],
    });
    input.dispatchEvent(new Event('change', { bubbles: true }));
    await flushPromises();
    await expect.poll(() => document.querySelectorAll('.exchange-row').length).toBe(100);
    for (const [width, height] of [
      [1063, 804],
      [390, 700],
    ]) {
      await page.viewport(width!, height!);
      await expect
        .poll(() => {
          const button = [...document.querySelectorAll<HTMLButtonElement>('button')].find(
            (item) => item.textContent?.trim() === 'Confirm import',
          );
          if (!button) return false;
          const rect = button.getBoundingClientRect();
          return rect.width > 0 && rect.left >= 0 && rect.right <= innerWidth && rect.bottom <= innerHeight;
        })
        .toBe(true);
      const body = [...document.querySelectorAll<HTMLElement>('.ui-modal-scroll-content')].find(
        (item) => item.getBoundingClientRect().height > 0,
      )!;
      expect(body.scrollHeight).toBeGreaterThan(body.clientHeight);
    }
  } finally {
    wrapper.unmount();
  }
});
