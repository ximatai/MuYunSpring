import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import type { OptionItemDescriptor } from '@muyun/web-contracts';
import { createLanguageContext, provideLanguageContext, type ModuleContext } from '@muyun/web-core';
import DictionaryPicker from '@/platform-components/DictionaryPicker.vue';
import DictionaryOptionDialog from '@/platform-components/DictionaryOptionDialog.vue';
import DictionaryRadioGroup from '@/platform-components/DictionaryRadioGroup.vue';
import RecordFormFields from '@/platform-components/RecordFormFields.vue';
import type { RecordFormFieldDescriptor } from '@/platform-components/recordFormFieldModel';

function item(code: string, title: string, english: string, enabled = true): OptionItemDescriptor {
  return {
    code,
    title,
    enabled,
    titleText: {
      source: {
        sourceKind: 'CONFIG',
        sourceId: `config/dictionary/item/${code}/title`,
        sourceVersion: 'v1',
        originalText: title,
      },
      languages: {
        en: { text: english, requestedTag: 'en', resourceTag: 'en', missing: false, fallback: false },
      },
    },
  };
}

function host(language: ReturnType<typeof createLanguageContext>, render: () => ReturnType<typeof h>) {
  return defineComponent({
    setup() {
      provideLanguageContext(language);
      return render;
    },
  });
}

it('projects cached dictionary options in a stable form and preserves draft codes across language changes', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const items = [item('M', '男', 'Male'), item('F', '女', 'Female')];
  const request = vi.fn(async () => items);
  const context = { moduleAlias: 'demo.person', http: { request } } as unknown as ModuleContext<unknown>;
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'gender',
      {
        fieldRef: { fieldName: 'gender' },
        label: 'Gender',
        fieldControl: { alias: 'dictionary_dropdown', rendererType: 'SELECT', valueShape: 'SCALAR' },
        option: { binding: { sourceType: 'dictionary', source: 'demo.gender' }, selectionMode: 'SINGLE' },
      },
    ],
  ]);
  const record = { gender: 'M', note: '未保存的业务原文' };
  const wrapper = mount(
    host(language, () =>
      h(RecordFormFields, {
        record,
        fields,
        mode: 'edit',
        optionContext: context,
      }),
    ),
  );
  try {
    await flushPromises();
    const select = wrapper.getComponent({ name: 'UiSelect' });
    expect(select.props('options')).toEqual([
      { label: 'Male', value: 'M', disabled: false },
      { label: 'Female', value: 'F', disabled: false },
    ]);
    expect(select.props('searchValue')).toBe('Male');
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(select.props('searchValue')).toBe('男');
    expect(select.props('options')[1].label).toBe('女');
    language.setLanguage('en');
    await flushPromises();
    expect(select.props('searchValue')).toBe('Male');
    expect(request).toHaveBeenCalledTimes(1);
    expect(record).toEqual({ gender: 'M', note: '未保存的业务原文' });
    expect(items[0]!.title).toBe('男');
    expect(wrapper.getComponent(RecordFormFields).emitted('update:field')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});

it('searches and completes visible translated titles while retaining code priority and disabled checks', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const items = [item('M', '男', 'Male'), item('F', '女', 'Female', false), item('other', '其他', 'M')];
  const wrapper = mount(host(language, () => h(DictionaryPicker, { items, mode: 'dropdown' })));
  try {
    const picker = wrapper.getComponent(DictionaryPicker);
    const select = wrapper.getComponent({ name: 'UiSelect' });
    select.vm.$emit('search', 'Female');
    await flushPromises();
    expect(select.props('options')).toEqual([{ label: 'Female', value: 'F', disabled: true }]);
    await picker.trigger('focusout', { relatedTarget: document.body });
    expect(picker.emitted('update:value')).toBeUndefined();
    expect(picker.emitted('validity-change')?.at(-1)).toEqual([
      expect.objectContaining({ valid: false, status: 'disabled' }),
    ]);
    select.vm.$emit('search', 'Male');
    await flushPromises();
    await picker.trigger('focusout', { relatedTarget: document.body });
    expect(picker.emitted('update:value')).toEqual([['M']]);
    // An exact stable code wins even when another candidate has that translated label.
    select.vm.$emit('search', 'M');
    await flushPromises();
    await picker.trigger('focusout', { relatedTarget: document.body });
    expect(picker.emitted('update:value')).toEqual([['M'], ['M']]);
    expect(items.map(({ title }) => title)).toEqual(['男', '女', '其他']);
  } finally {
    wrapper.unmount();
  }
});

it('keeps an open dialog draft and search while projecting hierarchy and selection in its own language', async () => {
  const language = createLanguageContext();
  const items = [
    item('parent', '分类', 'Category'),
    {
      ...item('child', '子项', 'Child'),
      parentCode: 'parent',
    },
    { code: 'custom', title: '用户原名', enabled: true },
  ];
  const wrapper = mount(
    host(language, () =>
      h(DictionaryOptionDialog, {
        open: true,
        items,
        value: ['parent'],
        selectionMode: 'MULTIPLE',
      }),
    ),
    {
      global: {
        stubs: {
          UiModal: { name: 'UiModal', emits: ['confirm'], template: '<section><slot /></section>' },
        },
      },
    },
  );
  try {
    const dialog = wrapper.getComponent(DictionaryOptionDialog);
    const choices = wrapper.getComponent({ name: 'DictionaryOptionDialogChoiceList' });
    choices.vm.$emit('update:selectedCodes', ['parent', 'child']);
    wrapper.getComponent({ name: 'UiSearchInput' }).vm.$emit('search', 'child');
    await flushPromises();
    language.setLanguage('en');
    await flushPromises();
    expect(choices.props('nodes')).toEqual([
      {
        value: 'parent',
        title: 'Category',
        disabled: false,
        children: [{ value: 'child', title: 'Child', disabled: false }],
      },
    ]);
    expect(choices.props('selectedCodes')).toEqual(['parent', 'child']);
    expect(wrapper.get('strong').text()).toBe('Category、Child');
    expect(wrapper.getComponent({ name: 'UiSearchInput' }).props('value')).toBe('child');
    expect(dialog.emitted('update:value')).toBeUndefined();
    wrapper.getComponent({ name: 'UiModal' }).vm.$emit('confirm');
    expect(dialog.emitted('update:value')).toEqual([[['parent', 'child']]]);
    expect(items[1]!.title).toBe('子项');
  } finally {
    wrapper.unmount();
  }
});

it('projects radio labels and compact summaries with fallback while emitting only enabled codes', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const items = [
    item('M', '男', 'Male'),
    item('F', '女', 'Female', false),
    { code: 'custom', title: '用户原名', enabled: true },
  ];
  const wrapper = mount(
    host(language, () =>
      h('div', [
        h(DictionaryPicker, { items, value: ['M', 'custom'], selectionMode: 'MULTIPLE' }),
        h(DictionaryRadioGroup, { items, value: 'M' }),
      ]),
    ),
  );
  try {
    const radio = wrapper.getComponent(DictionaryRadioGroup);
    const control = wrapper.getComponent({ name: 'UiRadioGroup' });
    expect(control.props('options')).toEqual([
      { label: 'Male', value: 'M', disabled: false },
      { label: 'Female', value: 'F', disabled: true },
      { label: '用户原名', value: 'custom', disabled: false },
    ]);
    expect(wrapper.getComponent({ name: 'ObjectPickerInput' }).props('value')).toBe('Male、用户原名');
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(control.props('options')[0].label).toBe('男');
    expect(wrapper.getComponent({ name: 'ObjectPickerInput' }).props('value')).toBe('男、用户原名');
    expect(radio.emitted('update:value')).toBeUndefined();
    control.vm.$emit('update:value', 'F');
    expect(radio.emitted('update:value')).toBeUndefined();
    control.vm.$emit('update:value', 'M');
    expect(radio.emitted('update:value')).toEqual([['M']]);
  } finally {
    wrapper.unmount();
  }
});
