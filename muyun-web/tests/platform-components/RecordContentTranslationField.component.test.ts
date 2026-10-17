import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { defineComponent, h, reactive, ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import {
  businessContentLanguageContext,
  createLanguageContext,
  provideLanguageContext,
  provideWorkspaceUnsavedStateRegistrar,
  type WorkspaceUnsavedStateRegistrar,
  type HttpRequestOptions,
  type ModuleContext,
  type BusinessContentRelationScope,
} from '@muyun/web-core';
import type {
  BusinessContentReading,
  BusinessContentTranslationMaintenance,
  BusinessContentTranslationRevision,
} from '@muyun/web-contracts';
import RecordContentTranslationField from '@/platform-components/RecordContentTranslationField.vue';

const confirmAction = vi.hoisted(() => vi.fn(async () => true));
vi.mock('@muyun/vue-ui-antdv', async (original) => ({
  ...(await original<typeof import('@muyun/vue-ui-antdv')>()),
  confirmAction,
}));

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
  relation?: BusinessContentRelationScope,
  policy: 'AUTO_TRANSLATE' | 'MANUAL_TRANSLATIONS' = 'MANUAL_TRANSLATIONS',
  fieldName: 'description' | 'reason' = 'description',
  registrar?: WorkspaceUnsavedStateRegistrar,
  mode: 'view' | 'edit' = 'view',
) {
  const language = createLanguageContext();
  const contentLanguage = businessContentLanguageContext(language);
  contentLanguage.setLanguage('en');
  const record = reactive({ id: 'child/one', version: 4, description: original.text, reason: original.text });
  const sessionKey = ref(1);
  const context = {
    http: { request },
    moduleAlias: 'quality.exception',
    can: (code: string) => actions.includes(code),
  } as unknown as ModuleContext<unknown>;
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        provideWorkspaceUnsavedStateRegistrar(registrar);
        return () =>
          h(RecordContentTranslationField, {
            record,
            fieldName,
            content: { key: 'stable-field', policy },
            context,
            relation,
            mode,
            sessionKey: sessionKey.value,
          });
      },
    }),
    { global: { stubs: { UiModal: Modal } } },
  );
  return { wrapper, language, contentLanguage, record, sessionKey };
}
function button(wrapper: VueWrapper, text: string) {
  const found = wrapper
    .findAll('button')
    .find((value) => value.text().replace(/\s+/g, '') === text.replace(/\s+/g, ''));
  expect(found, text).toBeDefined();
  return found!;
}
function requests(request: ReturnType<typeof vi.fn>) {
  return request.mock.calls.map((call) => call[0] as HttpRequestOptions);
}

describe('shared business translation reading and maintenance', () => {
  it.each([
    ['failed', '暂时无法读取译文，原文仍可使用'],
    ['fallback', '当前语言暂无有效译文，显示原文'],
    ['review', '原文已变更，旧人工译文待复核'],
  ] as const)(
    'keeps %s risk visible while editing with or without translation permissions',
    async (status, message) => {
      for (const actions of [[], ['contentEdit', 'contentConfirm']]) {
        const request = vi.fn(async () => {
          if (status === 'failed') throw new Error('reading unavailable');
          const result = reading();
          result.fields.description = {
            ...result.fields.description!,
            ...(status === 'fallback'
              ? { originalFallback: true, translation: null, text: original.text }
              : {}),
            humanReviewRequired: status === 'review',
          };
          return result;
        });
        const { wrapper } = host(
          request,
          actions,
          undefined,
          'MANUAL_TRANSLATIONS',
          'description',
          undefined,
          'edit',
        );
        await flushPromises();
        const notice = wrapper.findAll('[role="status"]').find((value) => value.text() === message);
        expect(notice, message).toBeDefined();
        expect(notice!.element.closest('details')).toBeNull();
        expect(wrapper.get('details').attributes('open')).toBeUndefined();
        if (!actions.length) expect(wrapper.text()).not.toContain('维护／核对译文');
        wrapper.unmount();
      }
    },
  );

  it.each(['unmount', 'record', 'session'] as const)(
    'does not execute a delayed delete confirmation after %s invalidates its editor',
    async (change) => {
      let finish!: (value: boolean) => void;
      const request = vi.fn(async (options: HttpRequestOptions) =>
        options.path.endsWith('/reading') ? reading() : options.path.endsWith('/history') ? [] : state(),
      );
      const { wrapper, record, sessionKey } = host(request);
      await flushPromises();
      await button(wrapper, '维护／核对译文').trigger('click');
      await flushPromises();
      confirmAction.mockImplementationOnce(() => new Promise<boolean>((resolve) => (finish = resolve)));
      await button(wrapper, '删除本语言译文').trigger('click');
      if (change === 'unmount') wrapper.unmount();
      else if (change === 'record') record.id = 'another-record';
      else sessionKey.value++;
      await flushPromises();
      finish(true);
      await flushPromises();
      expect(requests(request).some((r) => r.path.endsWith('/remove'))).toBe(false);
      if (change !== 'unmount') wrapper.unmount();
    },
  );
  it('protects unsaved input on close and registers it with the owning workspace', async () => {
    const unregister = vi.fn();
    const registerUnsavedState = vi.fn(() => unregister);
    const request = vi.fn(async (options: HttpRequestOptions) =>
      options.path.endsWith('/reading') ? reading() : options.path.endsWith('/history') ? [] : state(),
    );
    const { wrapper } = host(request, undefined, undefined, undefined, undefined, { registerUnsavedState });
    await flushPromises();
    const isDirty = (registerUnsavedState.mock.calls[0] as unknown as [string, () => boolean])[1];
    expect(isDirty()).toBe(false);
    await button(wrapper, '维护／核对译文').trigger('click');
    await flushPromises();
    await wrapper.get('textarea').setValue('Unsaved human translation');
    expect(isDirty()).toBe(true);
    confirmAction.mockResolvedValueOnce(false);
    wrapper.findComponent(Modal).vm.$emit('cancel');
    await flushPromises();
    expect(wrapper.get('textarea').element.value).toBe('Unsaved human translation');
    expect(isDirty()).toBe(true);
    confirmAction.mockResolvedValueOnce(true);
    wrapper.findComponent(Modal).vm.$emit('confirm');
    await flushPromises();
    expect(wrapper.find('textarea').exists()).toBe(false);
    expect(isDirty()).toBe(false);
    expect(requests(request).some((r) => r.method === 'POST' && !r.path.endsWith('/prepare'))).toBe(false);
    wrapper.unmount();
    expect(unregister).toHaveBeenCalledOnce();
  });

  it('does not remove a translation when its explicit confirmation is cancelled', async () => {
    const request = vi.fn(async (options: HttpRequestOptions) =>
      options.path.endsWith('/reading') ? reading() : options.path.endsWith('/history') ? [] : state(),
    );
    const { wrapper } = host(request);
    await flushPromises();
    await button(wrapper, '维护／核对译文').trigger('click');
    await flushPromises();
    confirmAction.mockResolvedValueOnce(false);
    await button(wrapper, '删除本语言译文').trigger('click');
    await flushPromises();
    expect(requests(request).some((r) => r.path.endsWith('/remove'))).toBe(false);
    expect(wrapper.get('textarea').element.value).toBe('Previous confirmed English');
    wrapper.unmount();
  });
  it('requests a manual-field machine draft only explicitly and preserves human input without a model', async () => {
    const request = vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/reading')) {
        const result = reading('en', original.text);
        result.fields.description!.originalFallback = true;
        result.fields.description!.translation = null;
        return result;
      }
      if (options.path.endsWith('/history')) return [];
      if (options.path.endsWith('/generation')) return { state: 'UNAVAILABLE', problem: 'MODEL_UNAVAILABLE' };
      return { ...state(), expectedRevision: null, confirmed: null };
    });
    const { wrapper, record } = host(request, ['contentEdit']);
    await flushPromises();
    expect(requests(request).some((row) => row.path.endsWith('/generation'))).toBe(false);
    await button(wrapper, '维护／核对译文').trigger('click');
    await flushPromises();
    expect(requests(request).some((row) => row.path.endsWith('/generation'))).toBe(false);
    await button(wrapper, '机器起草').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('自动翻译未启用');
    expect(requests(request).filter((row) => row.path.endsWith('/generation'))).toMatchObject([
      { method: 'POST', query: { retry: true } },
    ]);
    await wrapper.find('textarea').setValue('Keep my unsubmitted human revision');
    expect(button(wrapper, '机器起草').attributes('disabled')).toBeDefined();
    expect(record.description).toBe(original.text);
    expect(wrapper.find('textarea').element.value).toBe('Keep my unsubmitted human revision');
    wrapper.unmount();
  });

  it('opens and saves workflow maintenance receipts while retaining private drafts and the original event', async () => {
    let maintained = state();
    const request = vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/reading')) {
        const response = reading('en', maintained.confirmed?.text ?? 'Previous confirmed English');
        return { ...response, fields: { reason: response.fields.description } };
      }
      if (options.path.endsWith('/history')) return [];
      if (options.path.endsWith('/draft'))
        maintained = {
          ...maintained,
          expectedRevision: 3,
          draft: translation('DRAFT', 3, 'Human workflow reading'),
        };
      if (options.path.endsWith('/confirm'))
        maintained = {
          ...maintained,
          expectedRevision: 4,
          confirmed: translation('CONFIRMED', 4, maintained.draft!.text!),
        };
      return { data: maintained, changeSetId: 'workflow-maintenance', changes: [] };
    });
    const { wrapper, record } = host(
      request,
      ['contentEdit', 'contentConfirm'],
      { kind: 'workflowEvent', workflowInstanceId: 'instance' },
      'AUTO_TRANSLATE',
      'reason',
    );
    try {
      await flushPromises();
      await button(wrapper, '维护／核对译文').trigger('click');
      await flushPromises();
      expect(wrapper.find('[data-maintenance]').text()).toContain(original.text);
      await wrapper.find('textarea').setValue('Human workflow reading');
      await button(wrapper, '保存人工草稿').trigger('click');
      await flushPromises();
      expect(wrapper.find('.record-content-translation__text').text()).toContain(
        'Previous confirmed English',
      );
      await button(wrapper, '确认已审阅的草稿').trigger('click');
      await flushPromises();
      expect(wrapper.find('.record-content-translation__text').text()).toContain('Human workflow reading');
      expect(record.reason).toBe(original.text);
      expect(record.version).toBe(4);
      expect(
        requests(request)
          .filter((row) => row.method === 'POST')
          .every((row) =>
            row.path.startsWith(
              '/workflow/runtime/instance/instance/events/child%2Fone/content/reason/translations/en/',
            ),
          ),
      ).toBe(true);
    } finally {
      wrapper.unmount();
    }
  });
  it('clears the previous source history while a source switch is pending and retries the failed first page', async () => {
    const maintained = {
      ...state(),
      previousHuman: {
        original: { ...original, revisionId: 'earlier-original', revision: 2 },
        translation: translation('CONFIRMED', 8, 'Earlier reference text'),
      },
    };
    let currentLoads = 0;
    let rejectSwitch!: (error: Error) => void;
    const request = vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/reading')) return reading();
      if (!options.path.endsWith('/history')) return maintained;
      if (options.query?.sourceRevisionId === 'earlier-original') {
        return options.query.page === 1
          ? Array.from({ length: 20 }, (_, index) =>
              translation('DRAFT', 20 - index, `Earlier first page ${index}`),
            )
          : [translation('DRAFT', 1, 'Earlier history page two')];
      }
      if (++currentLoads === 2)
        return new Promise((_, reject) => {
          rejectSwitch = reject;
        });
      return [translation('CONFIRMED', 2, 'Current history row')];
    });
    const { wrapper } = host(request);
    await flushPromises();
    await button(wrapper, '维护／核对译文').trigger('click');
    await flushPromises();
    await button(wrapper, '查看旧参考的译文历史').trigger('click');
    await flushPromises();
    await button(wrapper, '下一页').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('Earlier history page two');
    await button(wrapper, '查看当前原文的译文历史').trigger('click');
    await flushPromises();
    expect.soft(wrapper.text()).not.toContain('Earlier history page two');
    rejectSwitch(new Error('history unavailable'));
    await flushPromises();
    expect.soft(wrapper.text()).not.toContain('Earlier history page two');
    expect(wrapper.text()).toContain('暂时无法读取历史，请重试');
    await button(wrapper, '重试').trigger('click');
    await flushPromises();
    expect(
      requests(request)
        .filter((row) => row.path.endsWith('/history'))
        .at(-1)?.query,
    ).toMatchObject({
      sourceRevisionId: original.revisionId,
      sourceRevision: original.revision,
      page: 1,
    });
    expect(wrapper.text()).toContain('Current history row');
    expect(wrapper.text()).not.toContain('Earlier history page two');
    wrapper.unmount();
  });

  it('reviews an earlier human translation without reusing its confirmation or original identity', async () => {
    const previousOriginal = {
      ...original,
      revisionId: 'earlier-original-id',
      revision: 2,
      text: '80°C 原文',
    };
    const previousHuman = {
      original: previousOriginal,
      translation: translation('CONFIRMED', 9, 'Earlier 80°C translation'),
    };
    const maintained: BusinessContentTranslationMaintenance = {
      original,
      expectedRevision: null,
      draft: null,
      confirmed: null,
      machine: null,
      previousHuman,
    };
    let confirmed = false;
    const request = vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/reading')) {
        if (confirmed) return reading('en', 'Reviewed current translation');
        const result = reading();
        result.fields.description = {
          ...result.fields.description!,
          text: original.text,
          actualLanguage: 'zh-CN',
          originalFallback: true,
          translation: null,
          humanReviewRequired: true,
        };
        return result;
      }
      if (options.path.endsWith('/history')) return [];
      if (options.path.endsWith('/draft'))
        return {
          ...maintained,
          expectedRevision: 1,
          draft: translation('DRAFT', 1, 'Reviewed current translation'),
        };
      if (options.path.endsWith('/confirm')) {
        confirmed = true;
        return {
          ...maintained,
          expectedRevision: 2,
          confirmed: translation('CONFIRMED', 2, 'Reviewed current translation'),
        };
      }
      return maintained;
    });
    const { wrapper, record } = host(request);
    await flushPromises();
    expect(wrapper.get('[data-translation-field]').text()).not.toContain('Earlier 80°C translation');
    expect(wrapper.get('[data-translation-field]').text()).toContain('旧人工译文待复核');
    await button(wrapper, '维护／核对译文').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-maintenance]').text()).toContain('旧确认状态不会沿用');
    expect(wrapper.get('textarea').element.value).toBe('');
    expect(button(wrapper, '确认已审阅的草稿').element.disabled).toBe(true);
    await button(wrapper, '查看旧参考的译文历史').trigger('click');
    await flushPromises();
    expect(
      requests(request)
        .filter((r) => r.path.endsWith('/history'))
        .at(-1)?.query,
    ).toMatchObject({ sourceRevisionId: previousOriginal.revisionId, sourceRevision: 2 });
    await button(wrapper, '复制到输入框后核对').trigger('click');
    await flushPromises();
    expect(wrapper.get('textarea').element.value).toBe('Earlier 80°C translation');
    expect(requests(request).filter((r) => r.method === 'POST')).toHaveLength(1);
    expect(button(wrapper, '确认已审阅的草稿').element.disabled).toBe(true);
    await wrapper.get('textarea').setValue('Reviewed current translation');
    expect(button(wrapper, '复制到输入框后核对').element.disabled).toBe(true);
    await button(wrapper, '保存人工草稿').trigger('click');
    await flushPromises();
    expect(requests(request).find((r) => r.path.endsWith('/draft'))?.body).toEqual({
      sourceRevisionId: original.revisionId,
      sourceRevision: 3,
      expectedRevision: null,
      text: 'Reviewed current translation',
    });
    await button(wrapper, '确认已审阅的草稿').trigger('click');
    await flushPromises();
    expect(requests(request).find((r) => r.path.endsWith('/confirm'))?.body).toEqual({
      sourceRevisionId: original.revisionId,
      sourceRevision: 3,
      expectedRevision: 1,
      reviewedDraftRevision: 1,
    });
    expect(wrapper.get('[data-translation-field]').text()).toContain('Reviewed current translation');
    expect(record.description).toBe(original.text);
    expect(record.version).toBe(4);
    wrapper.unmount();
  });

  it('keeps an earlier confirmed translation read-only for a confirm-only reviewer until a current draft exists', async () => {
    const request = vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/reading')) return reading();
      if (options.path.endsWith('/review-history')) return [];
      return {
        ...state(),
        expectedRevision: null,
        confirmed: null,
        previousHuman: {
          original: { ...original, revisionId: 'earlier-original', revision: 2 },
          translation: translation('CONFIRMED', 8, 'Earlier confirmation'),
        },
      };
    });
    const { wrapper } = host(request, ['contentConfirm']);
    await flushPromises();
    await button(wrapper, '维护／核对译文').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-maintenance]').text()).toContain('Earlier confirmation');
    expect(wrapper.get('textarea').element.disabled).toBe(true);
    expect(wrapper.findAll('button').some((b) => b.text() === '复制到输入框后核对')).toBe(false);
    expect(button(wrapper, '确认已审阅的草稿').element.disabled).toBe(true);
    await button(wrapper, '查看旧参考的译文历史').trigger('click');
    await flushPromises();
    expect(
      requests(request)
        .filter((r) => r.path.endsWith('/review-history'))
        .at(-1)?.query,
    ).toMatchObject({ sourceRevisionId: 'earlier-original', sourceRevision: 2 });
    expect(requests(request).some((r) => r.path.endsWith('/confirm'))).toBe(false);
    wrapper.unmount();
  });

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

it.each(['confirm', 'remove'] as const)(
  'refreshes beyond a delayed pre-mutation reading after %s without changing the business version',
  async (operation) => {
    let finishOld!: (value: BusinessContentReading) => void;
    let readCount = 0;
    const draft = translation('DRAFT', 3, 'New confirmed English');
    const request = vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/reading')) {
        readCount++;
        if (readCount === 2) return new Promise<BusinessContentReading>((done) => (finishOld = done));
        if (readCount === 1) return reading();
        return operation === 'confirm'
          ? reading('en', 'New confirmed English')
          : {
              ...reading(),
              fields: {
                description: {
                  originalText: original.text,
                  source: original,
                  text: original.text,
                  actualLanguage: 'zh-CN',
                  originalFallback: true,
                  translation: null,
                },
              },
            };
      }
      if (options.path.endsWith('/history')) return [];
      if (options.path.endsWith('/draft')) return { ...state(), expectedRevision: 3, draft };
      if (options.path.endsWith('/confirm'))
        return { ...state(), expectedRevision: 4, confirmed: translation('CONFIRMED', 4, draft.text!) };
      if (options.path.endsWith('/remove'))
        return { ...state(), expectedRevision: 4, draft: null, confirmed: null };
      return state();
    });
    const { wrapper, record } = host(request);
    await flushPromises();
    await button(wrapper, '维护／核对译文').trigger('click');
    await flushPromises();
    await wrapper.get('textarea').setValue(draft.text!);
    await button(wrapper, '保存人工草稿').trigger('click');
    await flushPromises();
    await button(wrapper, operation === 'confirm' ? '确认已审阅的草稿' : '删除本语言译文').trigger('click');
    await flushPromises();
    finishOld(reading());
    await flushPromises();
    expect(readCount).toBe(3);
    const projection = wrapper.get('[data-translation-field]').text();
    expect(projection).not.toContain('Previous confirmed English');
    expect(projection).toContain(operation === 'confirm' ? 'New confirmed English' : '当前语言暂无有效译文');
    expect(record.version).toBe(4);
    expect(record.description).toBe(original.text);
    wrapper.unmount();
  },
);

describe('automatic translation in the shared field', () => {
  function fallback(language = 'en'): BusinessContentReading {
    const result = reading(language);
    result.fields.description = {
      ...result.fields.description!,
      text: original.text,
      actualLanguage: 'zh-CN',
      originalFallback: true,
      translation: null,
    };
    return result;
  }
  it('shows unavailable automatic translation while retaining manual maintenance and the original', async () => {
    const request = vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/reading')) return fallback();
      if (options.path.endsWith('/generation')) return { state: 'UNAVAILABLE', problem: 'MODEL_UNAVAILABLE' };
      if (options.path.endsWith('/history')) return [];
      return state();
    });
    const { wrapper, record } = host(request, ['contentEdit', 'contentConfirm'], undefined, 'AUTO_TRANSLATE');
    await flushPromises();
    expect(wrapper.text()).toContain('自动翻译未启用，原文及人工译文仍可使用');
    expect(wrapper.text()).toContain('当前语言暂无有效译文，显示原文');
    expect(wrapper.findAll('button').some((value) => value.text().includes('重新申请'))).toBe(false);
    await button(wrapper, '维护／核对译文').trigger('click');
    await flushPromises();
    expect(wrapper.find('textarea').exists()).toBe(true);
    expect(record.description).toBe(original.text);
    wrapper.unmount();
  });

  it('requests a new attempt only through the explicit retry button', async () => {
    let retries = 0;
    const request = vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/reading')) return fallback();
      if (options.query?.retry === true) retries++;
      return { state: 'FAILED' };
    });
    const { wrapper } = host(request, [], undefined, 'AUTO_TRANSLATE');
    await flushPromises();
    expect(wrapper.text()).toContain('译文未生成');
    expect(retries).toBe(0);
    await button(wrapper, '重新申请自动翻译').trigger('click');
    await flushPromises();
    expect(retries).toBe(1);
    expect(requests(request).filter((row) => row.path.endsWith('/generation'))).toHaveLength(2);
    wrapper.unmount();
  });

  it('keeps both the business draft and open human translation input when reading language changes', async () => {
    const request = vi.fn(async (options: HttpRequestOptions) => {
      if (options.path.endsWith('/reading')) return fallback(String(options.query?.language));
      if (options.path.endsWith('/generation')) return { state: 'UNAVAILABLE' };
      if (options.path.endsWith('/history')) return [];
      return state();
    });
    const { wrapper, contentLanguage, record } = host(request, ['contentEdit'], undefined, 'AUTO_TRANSLATE');
    await flushPromises();
    await button(wrapper, '维护／核对译文').trigger('click');
    await flushPromises();
    await wrapper.find('textarea').setValue('Human translation still being edited');
    record.description = 'Unsaved business edit';
    await flushPromises();
    contentLanguage.setLanguage('de');
    await flushPromises();
    expect(wrapper.find('textarea').element.value).toBe('Human translation still being edited');
    expect(record.description).toBe('Unsaved business edit');
    expect(wrapper.text()).toContain('原文有未保存修改');
    expect(requests(request).filter((row) => row.path.endsWith('/generation'))).toHaveLength(1);
    wrapper.unmount();
  });
});
