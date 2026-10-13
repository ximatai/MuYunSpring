import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, onMounted, reactive } from 'vue';
import { expect, it, vi } from 'vitest';
import {
  createLanguageContext,
  createModuleContext,
  provideLanguageContext,
  type HttpClient,
} from '@muyun/web-core';
import { useNavigatorRuntime } from '@/dynamic-page-runtime/composables/useNavigatorRuntime';
import type { QueryListRecord } from '@muyun/platform-components';
import RecordFormFields from '@/platform-components/RecordFormFields.vue';

it('changes static labels and validation without remounting or replacing an unsaved business draft', async () => {
  const language = createLanguageContext();
  const record = reactive({ title: '' });
  const request = vi.fn(async () => ({
    moduleAlias: 'education.teacher',
    capabilities: [],
    abilities: ['crud'],
    actions: [],
    uiDescriptor: {
      schemaVersion: 'module-ui.v7',
      moduleAlias: 'education.teacher',
      defaultEditor: {
        viewCode: 'form',
        viewKind: 'FORM',
        fields: [
          {
            fieldRef: { fieldName: 'title' },
            label: 'Teacher name',
            required: { constant: true },
            valueType: 'STRING',
          },
        ],
      },
    },
    textBindings: {
      '/uiDescriptor/defaultEditor/fields/0/label': {
        source: {
          sourceKind: 'CODE',
          sourceId: 'ui/education.teacher/defaultEditor/fields/main/title/label',
          sourceVersion: 'original-1',
          originalText: '教师姓名',
        },
        languages: {
          en: {
            text: 'Teacher name',
            requestedTag: 'en',
            resourceTag: 'en',
            missing: false,
            fallback: false,
          },
        },
      },
    },
  }));
  const context = createModuleContext<QueryListRecord>({
    moduleAlias: 'education.teacher',
    language,
    http: { request } as unknown as HttpClient,
  });
  const Host = defineComponent({
    setup() {
      provideLanguageContext(language);
      const runtime = useNavigatorRuntime(context);
      onMounted(runtime.loadRuntimeDescriptor);
      return () =>
        h(RecordFormFields, {
          record,
          fields: runtime.formFields.value,
          mode: 'create',
          validationRequestKey: 1,
          'onUpdate:field': (name: string, value: unknown) => {
            record[name as 'title'] = String(value ?? '');
          },
        });
    },
  });
  const wrapper = mount(Host);
  try {
    await flushPromises();
    expect(wrapper.text()).toContain('教师姓名');
    expect(wrapper.text()).toContain('请填写教师姓名');
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.text()).toContain('Teacher name');
    expect(wrapper.text()).toContain('Enter Teacher name');
    const input = wrapper.get('input');
    await input.setValue('未保存的教师姓名');
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(wrapper.get('input').element).toBe(input.element);
    expect(wrapper.get('input').element.value).toBe('未保存的教师姓名');
    expect(record.title).toBe('未保存的教师姓名');
    expect(context.runtime.snapshot()?.uiDescriptor?.defaultEditor?.fields[0]?.label).toBe('教师姓名');
    expect(request).toHaveBeenCalledTimes(1);
  } finally {
    wrapper.unmount();
  }
});
