import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, reactive } from 'vue';
import { expect, it, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import {
  createLanguageContext,
  provideLanguageContext,
  type HttpRequestOptions,
  type ModuleContext,
} from '@muyun/web-core';
import RecordContentSourceField from '@/platform-components/RecordContentSourceField.vue';
import '@/styles.css';

it('keeps same-language reading quiet and exposes original history by keyboard', async () => {
  await page.viewport(992, 820);
  const language = createLanguageContext();
  const original = {
    revisionId: 'original-1',
    sourceKey: 'source-1',
    revision: 1,
    text: '原始中文',
    sourceLanguage: 'zh-CN',
    authorId: 'cn',
    createdAt: '2026-10-17T01:00:00Z',
  };
  const request = vi.fn(async (options: HttpRequestOptions) =>
    options.path.endsWith('/content')
      ? {
          recordVersion: 1,
          fields: {
            description: {
              content: { key: 'description', policy: 'MANUAL_TRANSLATIONS' },
              text: original.text,
              expectedRevision: 1,
              source: original,
            },
          },
        }
      : {
          recordVersion: 1,
          language: options.query?.language,
          fields: {
            description: {
              originalText: original.text,
              source: original,
              text: original.text,
              actualLanguage: 'zh-CN',
              originalFallback: false,
              translation: null,
            },
          },
        },
  );
  const record = reactive({ id: 'record-1', version: 1, description: original.text });
  const context = {
    moduleAlias: 'demo.product',
    http: { request },
    can: () => true,
  } as unknown as ModuleContext<unknown>;
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () =>
          h(RecordContentSourceField, {
            record,
            fieldName: 'description',
            content: { key: 'description', policy: 'MANUAL_TRANSLATIONS' },
            context,
            mode: 'view',
          });
      },
    }),
    { attachTo: document.body },
  );
  try {
    await flushPromises();
    const details = wrapper.get('details').element as HTMLDetailsElement;
    expect(details.open).toBe(false);
    expect(wrapper.get('.record-content-translation__text').text()).toBe(original.text);
    await expect.element(page.elementLocator(wrapper.get('button').element)).not.toBeVisible();
    expect(wrapper.text()).not.toContain('维护／核对译文');
    (wrapper.get('summary').element as HTMLElement).focus();
    await userEvent.keyboard('{Enter}');
    expect(details.open).toBe(true);
    expect(wrapper.get('button').element.getBoundingClientRect().height).toBeGreaterThan(0);
  } finally {
    wrapper.unmount();
  }
});
