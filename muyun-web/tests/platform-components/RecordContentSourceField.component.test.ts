import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, reactive } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import type { BusinessContentSources, BusinessContentLanguageInput } from '@muyun/web-contracts';
import RecordContentSourceField from '@/platform-components/RecordContentSourceField.vue';
import RecordFormFields from '@/platform-components/RecordFormFields.vue';
import type {
  RecordFormFieldDescriptor,
  RecordFormFieldValue,
} from '@/platform-components/recordFormFieldModel';

const policy = { key: 'stable-description', policy: 'AUTO_TRANSLATE' as const };
function source(text = '原始中文', revision = 3, tag = 'zh-CN'): BusinessContentSources {
  return {
    recordVersion: 4,
    fields: {
      description: {
        content: policy,
        text,
        expectedRevision: revision,
        source: {
          sourceKey: 'trusted-source',
          revision,
          text,
          sourceLanguage: tag,
          authorId: 'writer-id',
          authorName: 'cn_author',
          createdAt: '2026-10-16T01:00:00Z',
          authoredAt: '2026-10-16T01:00:00Z',
          revisionAuthorId: 'writer-id',
          revisionKind: 'ORIGINAL_WRITE',
        },
      },
    },
  };
}
const Modal = defineComponent({
  props: ['open'],
  setup:
    (props, { slots }) =>
    () =>
      props.open ? h('section', [slots.default?.(), slots.operation?.()]) : null,
});
function host(render: () => ReturnType<typeof h>, language = createLanguageContext()) {
  return {
    language,
    wrapper: mount(
      defineComponent({
        setup() {
          provideLanguageContext(language);
          return render;
        },
      }),
      // Translation reading has its own transport/race tests; these assertions own original-source intent.
      {
        global: {
          stubs: {
            UiModal: Modal,
            RecordContentTranslationField: defineComponent({
              setup:
                (_, { slots }) =>
                () =>
                  slots.source?.(),
            }),
          },
        },
      },
    ),
  };
}

describe('business original source controls', () => {
  it('does not adopt an old source response after a session-only switch with the same record snapshot', async () => {
    const callbacks: ((value: BusinessContentSources) => void)[] = [];
    const request = vi.fn(() => new Promise<BusinessContentSources>((resolve) => callbacks.push(resolve)));
    const state = reactive({ record: { id: 'one', version: 4, description: '原始中文' }, sessionKey: 1 });
    const context = {
      moduleAlias: 'demo.issue',
      http: { request },
    } as unknown as import('@muyun/web-core').ModuleContext<unknown>;
    const { wrapper } = host(() =>
      h(RecordContentSourceField, {
        record: state.record,
        fieldName: 'description',
        content: policy,
        context,
        mode: 'view',
        sessionKey: state.sessionKey,
      }),
    );
    await flushPromises();
    state.sessionKey = 2;
    await flushPromises();
    expect(callbacks).toHaveLength(2);
    const old = source();
    old.fields.description!.source!.authorName = 'old-session-author';
    callbacks[0]!(old);
    await flushPromises();
    expect(wrapper.text()).not.toContain('old-session-author');
    const current = source();
    current.fields.description!.source!.authorName = 'new-session-author';
    callbacks[1]!(current);
    await flushPromises();
    expect(wrapper.text()).toContain('new-session-author');
    expect(wrapper.text()).not.toContain('old-session-author');
    wrapper.unmount();
  });

  it('keeps raw editor values and source intent when the interface language changes', async () => {
    const request = vi.fn().mockResolvedValue(source());
    const state = reactive({
      record: { id: 'one', version: 4, description: '原始中文' } as Record<string, unknown>,
    });
    const fields = new Map<string, RecordFormFieldDescriptor>([
      [
        'description',
        {
          fieldRef: { fieldName: 'description' },
          label: '说明',
          uiType: 'textarea',
          content: policy,
        },
      ],
    ]);
    const context = {
      moduleAlias: 'demo.issue',
      http: { request },
    } as unknown as import('@muyun/web-core').ModuleContext<unknown>;
    const { wrapper, language } = host(() =>
      h(RecordFormFields, {
        record: state.record,
        fields,
        mode: 'edit',
        optionContext: context,
        'onUpdate:field': (field: string, value: RecordFormFieldValue) =>
          (state.record = { ...state.record, [field]: value }),
      }),
    );
    await flushPromises();
    const input = wrapper.get('textarea').element;
    expect(input.value).toBe('原始中文');
    expect(wrapper.text()).toContain('cn_author');
    const select = wrapper.findComponent({ name: 'UiSelect' });
    expect(select.props('value')).toBe('zh-CN');
    select.vm.$emit('update:value', 'de');
    await wrapper.get('textarea').setValue('Deutscher Originalentwurf');
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.get('textarea').element).toBe(input);
    expect(input.value).toBe('Deutscher Originalentwurf');
    expect(state.record.description).toBe('Deutscher Originalentwurf');
    expect(state.record.$contentLanguages).toEqual({
      description: { languageTag: 'de', expectedRevision: 3 },
    });
    expect(select.props('value')).toBe('de');
    expect(wrapper.text()).toContain('Language of this original text');
    expect(request).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('uses a separate original history read under its parent relation', async () => {
    const revision = source().fields.description!.source!;
    const request = vi
      .fn()
      .mockImplementation(async ({ path }: { path: string }) =>
        path.endsWith('/history') ? [revision] : source(),
      );
    const { wrapper } = host(() =>
      h(RecordContentSourceField, {
        record: { id: 'line', version: 4, description: '原始中文' },
        fieldName: 'description',
        content: policy,
        context: { moduleAlias: 'demo.issue', http: { request } },
        relation: { parentId: 'parent', relationCode: 'lines' },
        mode: 'view',
      }),
    );
    await flushPromises();
    const button = wrapper.findAll('button').find((value) => value.text() === '原文修订历史')!;
    await button.trigger('click');
    await flushPromises();
    expect(request).toHaveBeenLastCalledWith({
      path: '/demo.issue/view/parent/relations/lines/line/content/description/history',
      query: { page: 1, size: 20 },
    });
    expect(wrapper.find('.record-content-source__text').text()).toBe('原始中文');
    expect(wrapper.text()).toContain('简体中文');
    const close = wrapper.findAll('button').filter((button) => button.text().replace(/\s/g, '') === '关闭');
    expect(close).toHaveLength(1);
    await close[0]!.trigger('click');
    expect(wrapper.find('.record-content-source__revision').exists()).toBe(false);
    expect(wrapper.findComponent(RecordContentSourceField).emitted('change')).toBeUndefined();
    wrapper.unmount();
  });

  it('does not let delayed source responses replace the selected record or its reviewed version', async () => {
    const responses: ((value: BusinessContentSources) => void)[] = [];
    const request = vi
      .fn()
      .mockImplementation(() => new Promise<BusinessContentSources>((resolve) => responses.push(resolve)));
    const state = reactive({
      record: { id: 'a', version: 1, description: '原始中文' } as Record<string, unknown>,
    });
    const { wrapper } = host(() =>
      h(RecordContentSourceField, {
        record: state.record,
        fieldName: 'description',
        content: policy,
        context: { moduleAlias: 'demo.issue', http: { request } },
        mode: 'edit',
        onChange: (inputs: Record<string, BusinessContentLanguageInput>) =>
          (state.record = { ...state.record, $contentLanguages: inputs }),
      }),
    );
    await flushPromises();
    state.record = { id: 'b', version: 2, description: 'English original' };
    await flushPromises();
    responses[1]!(source('English original', 9, 'en'));
    await flushPromises();
    responses[0]!(source());
    await flushPromises();
    const select = wrapper.findComponent({ name: 'UiSelect' });
    expect(select.props('value')).toBe('en');
    select.vm.$emit('update:value', 'de');
    await flushPromises();
    expect(state.record.$contentLanguages).toEqual({
      description: { languageTag: 'de', expectedRevision: 9 },
    });
    expect(state.record.description).toBe('English original');
    wrapper.unmount();
  });

  it('ignores late metadata after the same draft object receives a newer saved revision', async () => {
    const done: ((value: BusinessContentSources) => void)[] = [];
    const request = vi
      .fn()
      .mockImplementation(() => new Promise<BusinessContentSources>((resolve) => done.push(resolve)));
    const context = { moduleAlias: 'demo.issue', http: { request } };
    const state = reactive({
      record: { id: 'same', version: 4, description: 'Original' } as Record<string, unknown>,
    });
    const { wrapper } = host(() =>
      h(RecordContentSourceField, {
        record: state.record,
        fieldName: 'description',
        content: policy,
        context,
        mode: 'edit',
        onChange: (inputs: Record<string, BusinessContentLanguageInput>) =>
          (state.record = { ...state.record, $contentLanguages: inputs }),
      }),
    );
    await flushPromises();
    state.record.version = 5;
    await flushPromises();
    expect(request).toHaveBeenCalledTimes(2);
    done[1]!({ ...source('Original', 4, 'en-US'), recordVersion: 5 });
    await flushPromises();
    done[0]!(source('Original', 3, 'en'));
    await flushPromises();
    const select = wrapper.findComponent({ name: 'UiSelect' });
    expect(select.props('value')).toBe('en-US');
    select.vm.$emit('update:value', 'de');
    await flushPromises();
    expect(state.record.$contentLanguages).toEqual({
      description: { languageTag: 'de', expectedRevision: 4 },
    });
    expect(state.record.description).toBe('Original');
    wrapper.unmount();
  });

  it('preserves original drafts when source loading fails and can recover without adopting an interface language', async () => {
    const request = vi.fn().mockRejectedValueOnce(new Error('unavailable')).mockResolvedValue(source());
    const record = { id: 'one', version: 4, description: '原始中文' };
    const { wrapper } = host(() =>
      h(RecordContentSourceField, {
        record,
        fieldName: 'description',
        content: policy,
        context: { moduleAlias: 'demo.issue', http: { request } },
        mode: 'edit',
      }),
    );
    await flushPromises();
    expect(wrapper.findComponent({ name: 'UiSelect' }).props('disabled')).toBe(true);
    expect(wrapper.get('[role="status"]').text()).toContain('暂时无法核实来源');
    await wrapper.get('[role="status"] button').trigger('click');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'UiSelect' }).props('value')).toBe('zh-CN');
    expect(record.description).toBe('原始中文');
    expect(wrapper.findComponent(RecordContentSourceField).emitted('change')).toBeUndefined();
    wrapper.unmount();
  });
});
