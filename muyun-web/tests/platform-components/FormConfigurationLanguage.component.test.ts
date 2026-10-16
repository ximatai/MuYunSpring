import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { createLanguageContext, provideLanguageContext, type HttpClient } from '@muyun/web-core';
import { UiSearchInput } from '@muyun/vue-ui-antdv';
import RecordFormFields from '@/platform-components/RecordFormFields.vue';
import { missingReferencePickerDependencies } from '@/platform-components/referencePickerReadError';
import ReferencePicker from '@/platform-components/ReferencePicker.vue';
import WorkflowParticipantEditor from '@/platform-components/WorkflowParticipantEditor.vue';
import WorkflowBusinessTaskEditor from '@/platform-components/WorkflowBusinessTaskEditor.vue';
import type { RecordFormFieldDescriptor } from '@/platform-components/recordFormFieldModel';

function host(language: ReturnType<typeof createLanguageContext>, render: () => ReturnType<typeof h>) {
  return defineComponent({
    setup() {
      provideLanguageContext(language);
      return render;
    },
  });
}

it('updates invalid editor messages and override labels without changing the draft or input', async () => {
  const language = createLanguageContext();
  const record = { payload: { name: '原始数据' }, enabled: true };
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'payload',
      {
        fieldRef: { fieldName: 'payload' },
        label: '业务原名',
        fieldControl: { alias: 'json', rendererType: 'JSON', valueShape: 'SCALAR' },
      },
    ],
    [
      'override',
      {
        fieldRef: { fieldName: 'override' },
        label: '覆盖',
        valueType: 'BOOLEAN',
        uiType: 'switch',
        overrideOf: 'enabled',
      },
    ],
  ]);
  const wrapper = mount(host(language, () => h(RecordFormFields, { record, fields, mode: 'edit' })));
  try {
    const input = wrapper.get('textarea').element;
    await wrapper.get('textarea').setValue('{bad');
    expect(wrapper.get('[role="alert"]').text()).toBe('请输入有效 JSON');
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toBe('Enter valid JSON');
    expect(wrapper.get('textarea').element).toBe(input);
    expect(wrapper.get('textarea').element.value).toBe('{bad');
    expect(wrapper.findComponent({ name: 'UiSelect' }).props('options')).toEqual([
      { value: '__muyun_inherit__', label: 'Inherit (On)' },
      { value: 'true', label: 'On' },
      { value: 'false', label: 'Off' },
    ]);
    expect(record).toEqual({ payload: { name: '原始数据' }, enabled: true });
    expect(wrapper.findComponent(RecordFormFields).emitted('update:field')).toBeUndefined();
    expect(wrapper.findComponent(RecordFormFields).emitted('validity-change')?.at(-1)).toEqual([
      { valid: false, errors: { payload: 'Enter valid JSON' } },
    ]);
  } finally {
    wrapper.unmount();
  }
});

it('changes reference picker default operations and retains selected business titles and IDs', async () => {
  const language = createLanguageContext();
  const candidate = { id: 'stable-id', title: '业务原始标题' };
  const provider = {
    identity: {
      targetModuleAlias: 'demo.reference',
      source: { kind: 'sourceField' as const, id: 'ownerId' },
    },
    searchPage: vi.fn(async () => ({ records: [candidate], total: 1 })),
    resolve: vi.fn(async () => [candidate]),
  };
  const wrapper = mount(
    host(language, () => h(ReferencePicker, { provider, value: ['stable-id'], multiple: true })),
    {
      global: {
        stubs: {
          UiModal: {
            name: 'UiModal',
            props: ['open', 'title'],
            template: '<section v-if="open"><h2>{{title}}</h2><slot /></section>',
          },
          UiDataTable: true,
        },
      },
    },
  );
  try {
    await flushPromises();
    wrapper.findComponent({ name: 'ObjectPickerInput' }).vm.$emit('browse');
    await flushPromises();
    expect(wrapper.text()).toContain('已选 1 个记录');
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.get('h2').text()).toBe('Select reference records');
    expect(wrapper.text()).toContain('Selected: 1');
    expect(wrapper.text()).toContain('Clear selection');
    expect(
      wrapper.findAllComponents(UiSearchInput).some((item) => item.props('searchText') === 'Search'),
    ).toBe(true);
    expect(wrapper.text()).toContain('业务原始标题');
    expect(wrapper.findComponent(ReferencePicker).emitted('update:value')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});

it('translates workflow editor controls and errors while retaining authored configuration', async () => {
  const language = createLanguageContext();
  const value = JSON.stringify({
    task: {
      definition: { moduleAlias: 'demo.task' },
      checks: [],
      guides: [
        {
          guideKey: 'guide',
          title: '用户编写的指引',
          guideKind: 'EXECUTE_ACTION',
          targetActionCode: 'receive',
          guideConfigText: '{"payload":{}}',
        },
      ],
    },
  });
  const wrapper = mount(
    host(language, () =>
      h('div', [
        h(WorkflowParticipantEditor, {
          http: {
            request: vi.fn(async () => ({ moduleAlias: 'iam.user', capabilities: [], actions: [] })),
          } as HttpClient,
          value: '{"rules":',
        }),
        h(WorkflowBusinessTaskEditor, { moduleAlias: 'demo.task', value }),
      ]),
    ),
  );
  try {
    const nameInput = wrapper
      .findAllComponents({ name: 'UiInput' })
      .find((item) => item.props('value') === '用户编写的指引')!;
    const input = nameInput.get('input').element;
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.text()).toContain('The participant rules are invalid. Configure them again.');
    expect(wrapper.text()).toContain('Add participant rule');
    expect(wrapper.text()).toContain('Guide name');
    expect(wrapper.text()).toContain('Delete guide');
    const choices = wrapper
      .findAllComponents({ name: 'UiSelect' })
      .find((item) => item.props('value') === 'EXECUTE_ACTION')!;
    expect(choices.props('options')).toContainEqual({
      value: 'EXECUTE_ACTION',
      label: 'Execute an action and complete the task',
    });
    expect(nameInput.get('input').element).toBe(input);
    expect(nameInput.props('value')).toBe('用户编写的指引');
    expect(wrapper.findComponent(WorkflowBusinessTaskEditor).emitted('update:value')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});

it('refreshes an already displayed missing-dependency error without retrying or changing the reference', async () => {
  const language = createLanguageContext();
  const resolve = vi.fn(async () => {
    throw missingReferencePickerDependencies(['classId']);
  });
  const provider = {
    identity: {
      targetModuleAlias: 'demo.reference',
      source: { kind: 'sourceField' as const, id: 'studentId' },
    },
    resolve,
    searchPage: vi.fn(async () => ({ records: [], total: 0 })),
  };
  const wrapper = mount(host(language, () => h(ReferencePicker, { provider, value: 'student-id' })));
  try {
    await flushPromises();
    expect(wrapper.text()).toContain('请先补齐引用所需的依赖字段');
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.text()).toContain('Complete the fields required by this reference first');
    expect(wrapper.text()).not.toContain('请先补齐引用所需的依赖字段');
    expect(resolve).toHaveBeenCalledTimes(1);
    expect(wrapper.findComponent(ReferencePicker).emitted('update:value')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});
