import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, reactive } from 'vue';
import { expect, it, vi } from 'vitest';
import {
  createLanguageContext,
  createModuleContext,
  displayModuleRuntime,
  provideLanguageContext,
  type HttpClient,
} from '@muyun/web-core';
import { resolveRecordFormFields } from '@muyun/platform-components';
import RecordFormFields from '@/platform-components/RecordFormFields.vue';

it('keeps main and child drafts through language switches and translation publication and saves only original values', async () => {
  const language = createLanguageContext();
  const draft = reactive({ title: '', items: [{ note: '' }] });
  let mainTranslation = 'Exam name';
  const mainPath = '/uiDescriptor/defaultEditor/fields/0/label';
  const childPath = '/uiDescriptor/editorContributions/0/editor/fields/0/label';
  const binding = (id: string, original: string, text: string) => ({
    source: { sourceKind: 'CONFIG', sourceId: id, sourceVersion: 'v1', originalText: original },
    languages: { en: { text, requestedTag: 'en', resourceTag: 'en', missing: false, fallback: false } },
  });
  const request = vi.fn(async ({ path, body }: { path: string; body?: unknown }) => {
    if (path.endsWith('/insert')) return { data: { id: 'saved', ...(body as object) } };
    return {
      moduleAlias: 'test.exam',
      moduleKind: 'dynamic',
      capabilities: [],
      abilities: ['crud'],
      actions: [],
      uiDescriptor: {
        moduleAlias: 'test.exam',
        defaultEditor: {
          viewKind: 'FORM',
          fields: [{ fieldRef: { fieldName: 'title' }, label: '考试名称', valueType: 'STRING' }],
        },
        editorContributions: [
          {
            resource: 'item',
            editor: {
              viewKind: 'FORM',
              fields: [
                {
                  fieldRef: { relationCode: 'item', fieldName: 'note' },
                  label: '参考说明',
                  valueType: 'STRING',
                },
              ],
            },
          },
        ],
      },
      textBindings: {
        [mainPath]: binding('main.title', '考试名称', mainTranslation),
        [childPath]: binding('child.note', '参考说明', 'Participant note'),
      },
    };
  });
  const context = createModuleContext({
    moduleAlias: 'test.exam',
    language,
    http: { request } as unknown as HttpClient,
  });
  await context.runtime.ready;
  const facts = context.runtime.snapshot()?.uiDescriptor;
  const Host = defineComponent({
    setup() {
      provideLanguageContext(language);
      const fields = computed(() =>
        resolveRecordFormFields(displayModuleRuntime(context.runtime)?.uiDescriptor),
      );
      const childFields = computed(() =>
        resolveRecordFormFields(displayModuleRuntime(context.runtime)?.uiDescriptor, 'item'),
      );
      return () =>
        h('div', [
          h(RecordFormFields, {
            record: draft,
            fields: fields.value,
            mode: 'create',
            'onUpdate:field': (key: string, value: unknown) => {
              if (key === 'title') draft.title = String(value);
            },
          }),
          h(RecordFormFields, {
            record: draft.items[0],
            fields: childFields.value,
            mode: 'create',
            'onUpdate:field': (key: string, value: unknown) => {
              if (key === 'note') draft.items[0].note = String(value);
            },
          }),
        ]);
    },
  });
  const wrapper = mount(Host);
  try {
    const inputs = wrapper.findAll('input');
    await inputs[0].setValue('中文主表未保存内容');
    await inputs[1].setValue('中文明细未保存内容');
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.text()).toContain('Exam name');
    expect(wrapper.text()).toContain('Participant note');
    mainTranslation = 'Examination name';
    await context.runtime.refreshTexts!();
    await flushPromises();
    expect(wrapper.text()).toContain('Examination name');
    expect(wrapper.findAll('input')[0].element).toBe(inputs[0].element);
    expect(wrapper.findAll('input')[1].element).toBe(inputs[1].element);
    expect(draft).toEqual({ title: '中文主表未保存内容', items: [{ note: '中文明细未保存内容' }] });
    expect(context.runtime.snapshot()?.uiDescriptor).toBe(facts);
    const payload = {
      values: { title: draft.title },
      children: { items: draft.items.map((row) => ({ values: { ...row } })) },
    };
    await context.crud.insert(payload);
    expect(request).toHaveBeenLastCalledWith({ method: 'POST', path: '/test.exam/insert', body: payload });
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(wrapper.text()).toContain('考试名称');
    expect(draft.items[0].note).toBe('中文明细未保存内容');
  } finally {
    wrapper.unmount();
  }
});
