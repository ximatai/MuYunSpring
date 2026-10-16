import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import {
  businessContentLanguageContext,
  createLanguageContext,
  provideLanguageContext,
  type HttpRequestOptions,
  type ModuleContext,
} from '@muyun/web-core';
import type {
  BusinessContentReading,
  BusinessContentTranslationMaintenance,
  BusinessContentTranslationRevision,
} from '@muyun/web-contracts';
import RecordContentTranslationField from '@/platform-components/RecordContentTranslationField.vue';

const original = {
  revisionId: 'immutable-original-id',
  sourceKey: 'owned-source',
  revision: 3,
  text: '原始中文',
  sourceLanguage: 'zh-CN',
  authorId: 'cn',
  authorName: '中国作者',
  createdAt: '2026-10-16T01:00:00Z',
  authoredAt: '2026-10-16T01:00:00Z',
  revisionAuthorId: 'cn',
  revisionKind: 'ORIGINAL_WRITE' as const,
};
function translation(
  kind: BusinessContentTranslationRevision['kind'],
  revision: number,
  text: string,
): BusinessContentTranslationRevision {
  return {
    kind,
    revision,
    text,
    authorId: 'us',
    authorName: 'US reviewer',
    createdAt: '2026-10-16T01:00:00Z',
    reviewedDraftRevision: null,
    generationReference: null,
  };
}
function state(): BusinessContentTranslationMaintenance {
  return {
    original,
    expectedRevision: 2,
    draft: null,
    confirmed: translation('CONFIRMED', 2, 'Previous confirmed English'),
    machine: null,
  };
}
function reading(
  language = 'en',
  text = 'Previous confirmed English',
  raw = original.text,
): BusinessContentReading {
  return {
    recordVersion: 4,
    language,
    fields: {
      description: {
        originalText: raw,
        source: original,
        text,
        actualLanguage: language,
        originalFallback: false,
        translation: translation('CONFIRMED', 2, text),
      },
    },
  };
}
const Modal = defineComponent({
  props: ['open'],
  setup:
    (props, { slots }) =>
    () =>
      props.open ? h('section', { 'data-maintenance': '' }, slots.default?.()) : null,
});
function host(
  request: ReturnType<typeof vi.fn>,
  actions = ['contentEdit', 'contentConfirm'],
  relation?: { parentId: string; relationCode: string },
) {
  const language = createLanguageContext();
  const contentLanguage = businessContentLanguageContext(language);
  contentLanguage.setLanguage('en');
  const record = { id: 'child/one', version: 4, description: original.text };
  const context = {
    http: { request },
    moduleAlias: 'quality.exception',
    can: (code: string) => actions.includes(code),
  } as unknown as ModuleContext<unknown>;
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () =>
          h(RecordContentTranslationField, {
            record,
            fieldName: 'description',
            content: { key: 'stable-field', policy: 'MANUAL_TRANSLATIONS' },
            context,
            relation,
            mode: 'view',
          });
      },
    }),
    { global: { stubs: { UiModal: Modal } } },
  );
  return { wrapper, language, contentLanguage, record };
}
function button(wrapper: VueWrapper, text: string) {
  const found = wrapper.findAll('button').find((value) => value.text() === text);
  expect(found, text).toBeDefined();
  return found!;
}
function requests(request: ReturnType<typeof vi.fn>) {
  return request.mock.calls.map((call) => call[0] as HttpRequestOptions);
}

describe('shared business translation reading and maintenance', () => {
  it('keeps the effective reading and raw value when a human draft is saved through the owned child route', async () => {
    const maintained = state();
    const request = vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/reading')) return reading(String(options.query?.language));
      if (options.path.endsWith('/history')) return [];
      if (options.path.endsWith('/draft'))
        return { ...maintained, expectedRevision: 3, draft: translation('DRAFT', 3, 'New human draft') };
      return maintained;
    });
    const { wrapper, record } = host(request, undefined, {
      parentId: 'parent/one',
      relationCode: 'inspections',
    });
    await flushPromises();
    expect(wrapper.text()).toContain('Previous confirmed English');
    await button(wrapper, '维护／核对译文').trigger('click');
    await flushPromises();
    await wrapper.get('textarea').setValue('New human draft');
    await button(wrapper, '保存人工草稿').trigger('click');
    await flushPromises();
    const writes = requests(request).filter((r) => r.method === 'POST');
    expect(writes.map((r) => r.path)).toEqual([
      '/quality.exception/view/parent%2Fone/relations/inspections/child%2Fone/content/description/translations/en/prepare',
      '/quality.exception/view/parent%2Fone/relations/inspections/child%2Fone/content/description/translations/en/draft',
    ]);
    expect(writes[1]!.body).toEqual({
      sourceRevisionId: original.revisionId,
      sourceRevision: 3,
      expectedRevision: 2,
      text: 'New human draft',
    });
    expect(wrapper.get('textarea').element.value).toBe('New human draft');
    expect(wrapper.get('[data-translation-field]').text()).toContain('Previous confirmed English');
    expect(record.description).toBe(original.text);
    wrapper.unmount();
  });

  it('pins review language and retains typed translation on interface changes and rejected saves', async () => {
    const request = vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/reading')) return reading(String(options.query?.language));
      if (options.path.endsWith('/history')) return [];
      if (options.path.endsWith('/draft')) throw new Error('409 original changed');
      return state();
    });
    const { wrapper, language, contentLanguage, record } = host(request);
    await flushPromises();
    await button(wrapper, '维护／核对译文').trigger('click');
    await flushPromises();
    await wrapper.get('textarea').setValue('Unsaved English work');
    language.setLanguage('en');
    contentLanguage.setLanguage('de');
    await flushPromises();
    expect(wrapper.get('textarea').element.value).toBe('Unsaved English work');
    expect(wrapper.get('[data-maintenance]').text()).toContain('Language for this review：en');
    await button(wrapper, 'Save human draft').trigger('click');
    await flushPromises();
    expect(requests(request).find((r) => r.path.endsWith('/draft'))?.path).toContain(
      '/translations/en/draft',
    );
    expect(wrapper.get('textarea').element.value).toBe('Unsaved English work');
    expect(wrapper.get('[role="alert"]').text()).toContain('Your input is retained');
    expect(record.description).toBe(original.text);
    wrapper.unmount();
  });

  it('allows a confirm-only reviewer to confirm only the loaded draft without editing original or translation', async () => {
    const draft = translation('DRAFT', 3, 'Reviewed English');
    const request = vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/reading')) return reading();
      if (options.path.endsWith('/review-history')) return [draft];
      return { ...state(), expectedRevision: 3, draft };
    });
    const { wrapper } = host(request, ['contentConfirm']);
    await flushPromises();
    await button(wrapper, '维护／核对译文').trigger('click');
    await flushPromises();
    expect(wrapper.get('textarea').element.disabled).toBe(true);
    expect(wrapper.findAll('button').some((b) => b.text() === '保存人工草稿')).toBe(false);
    await button(wrapper, '确认已审阅的草稿').trigger('click');
    await flushPromises();
    const confirm = requests(request).find((r) => r.path.endsWith('/confirm'))!;
    expect(confirm.body).toEqual({
      sourceRevisionId: original.revisionId,
      sourceRevision: 3,
      expectedRevision: 3,
      reviewedDraftRevision: 3,
    });
    expect(requests(request).some((r) => r.path.endsWith('/review'))).toBe(true);
    expect(requests(request).some((r) => r.path.endsWith('/review-history'))).toBe(true);
    wrapper.unmount();
  });

  it('hides maintenance from view-only users and refuses to present a mismatched raw snapshot as current translation', async () => {
    const request = vi.fn(async () => reading('en', 'Outdated translation', 'Different original'));
    const { wrapper } = host(request, []);
    await flushPromises();
    expect(wrapper.text()).not.toContain('Outdated translation');
    expect(wrapper.text()).toContain('原文有未保存修改');
    expect(wrapper.findAll('button').some((b) => b.text() === '维护／核对译文')).toBe(false);
    wrapper.unmount();
  });

  it('does not let a delayed old language response replace the newly selected reading language', async () => {
    let complete!: (value: BusinessContentReading) => void;
    const request = vi.fn((options: HttpRequestOptions) =>
      options.query?.language === 'en'
        ? new Promise<BusinessContentReading>((done) => (complete = done))
        : Promise.resolve(reading('de', 'Deutsche Übersetzung')),
    );
    const { wrapper, contentLanguage } = host(request);
    contentLanguage.setLanguage('de');
    await flushPromises();
    complete(reading('en', 'Late English'));
    await flushPromises();
    expect(wrapper.text()).toContain('Deutsche Übersetzung');
    expect(wrapper.text()).not.toContain('Late English');
    wrapper.unmount();
  });
});
