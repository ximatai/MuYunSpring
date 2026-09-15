import { mount, shallowMount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import DictionaryOptionDialogChoiceList from '@/platform-components/DictionaryOptionDialogChoiceList.vue';
import DictionaryOptionDialog from '@/platform-components/DictionaryOptionDialog.vue';
import DictionaryPicker from '@/platform-components/DictionaryPicker.vue';
import DictionaryRadioGroup from '@/platform-components/DictionaryRadioGroup.vue';

const items = [
  { code: 'enabled', title: '启用', enabled: true },
  { code: 'disabled', title: '停用', enabled: false },
  { code: 'parent', title: '一级分类', enabled: true },
  { code: 'child', title: '二级分类', enabled: true, parentCode: 'parent' },
];

function mountDialog(overrides: Record<string, unknown> = {}) {
  return shallowMount(DictionaryOptionDialog, {
    props: { open: true, items, ...overrides },
    global: {
      stubs: {
        UiModal: {
          name: 'UiModal',
          props: ['open', 'confirmDisabled', 'closable'],
          emits: ['confirm', 'cancel'],
          template: '<section v-if="open"><slot /></section>',
        },
        UiButton: {
          name: 'UiButton',
          props: ['disabled'],
          emits: ['click'],
          template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
        },
        UiSearchInput: {
          name: 'UiSearchInput',
          props: ['value'],
          emits: ['update:value', 'search'],
          template: '<input :value="value" />',
        },
        DictionaryOptionDialogChoiceList: {
          name: 'DictionaryOptionDialogChoiceList',
          props: ['nodes', 'selectedCodes', 'selectionMode'],
          emits: ['update:selectedCodes', 'double-click'],
          template: '<div />',
        },
      },
    },
  });
}

function mountInteractiveDialog(overrides: Record<string, unknown> = {}) {
  return mount(DictionaryOptionDialog, {
    props: { open: true, items, ...overrides },
    global: {
      stubs: {
        UiModal: {
          name: 'UiModal',
          props: ['open', 'confirmDisabled', 'closable'],
          emits: ['confirm', 'cancel'],
          template: '<section v-if="open"><slot /></section>',
        },
        UiButton: {
          name: 'UiButton',
          props: ['disabled'],
          emits: ['click'],
          template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
        },
        UiSearchInput: {
          name: 'UiSearchInput',
          props: ['value'],
          emits: ['update:value', 'search'],
          template: '<input :value="value" />',
        },
      },
    },
  });
}

function mountPicker(overrides: Record<string, unknown> = {}) {
  return shallowMount(DictionaryPicker, {
    props: { items, ...overrides },
    global: {
      stubs: {
        UiSelect: {
          name: 'UiSelect',
          props: ['value', 'options', 'mode', 'searchValue', 'unmatched'],
          emits: ['search', 'update:searchValue', 'blur', 'update:value', 'dblclick'],
          template: '<div><input /><slot name="suffixAction" /></div>',
        },
        UiTreeSelect: {
          name: 'UiTreeSelect',
          props: ['value', 'treeData', 'mode', 'searchValue', 'unmatched'],
          emits: ['search', 'update:searchValue', 'blur', 'update:value', 'dblclick'],
          template: '<div><input /><slot name="suffixAction" /></div>',
        },
        ObjectPickerInput: {
          name: 'ObjectPickerInput',
          props: ['value', 'selectionVersion', 'linked'],
          emits: ['browse', 'clear', 'blur'],
          template: '<div />',
        },
        DictionaryOptionDialog: {
          name: 'DictionaryOptionDialog',
          props: ['open', 'value', 'items', 'selectionMode', 'initialKeyword'],
          emits: ['update:open', 'update:value', 'confirm', 'cancel', 'clear'],
          template: '<section v-if="open" />',
        },
      },
    },
  });
}

it('renders flat dialog candidates as direct choices instead of another select control', async () => {
  const wrapper = shallowMount(DictionaryOptionDialogChoiceList, {
    props: {
      nodes: [{ value: 'enabled', title: '启用', disabled: false }],
      selectedCodes: [],
    },
  });

  expect(wrapper.findAll('button')).toHaveLength(1);
  expect(wrapper.find('button').attributes('role')).toBe('radio');
  await wrapper.find('button').trigger('click');
  expect(wrapper.emitted('update:selectedCodes')).toEqual([[['enabled']]]);
  await wrapper.find('button').trigger('dblclick');
  expect(wrapper.emitted('double-click')).toEqual([['enabled']]);
});

it('double-clicking a single dictionary choice confirms it and closes the dialog', async () => {
  const wrapper = mountInteractiveDialog({ items: items.slice(0, 2) });
  await wrapper.get('[role="radio"]').trigger('dblclick');
  await wrapper.vm.$nextTick();

  expect(wrapper.emitted('update:value')).toEqual([['enabled']]);
  expect(wrapper.emitted('confirm')).toEqual([['enabled']]);
  expect(wrapper.emitted('update:open')).toEqual([[false]]);
});

it('renders a dictionary child in an explicitly nested choice list', () => {
  const wrapper = mount(DictionaryOptionDialogChoiceList, {
    props: {
      nodes: [
        {
          value: 'parent',
          title: '一级分类',
          children: [{ value: 'child', title: '二级分类' }],
        },
      ],
      selectedCodes: [],
    },
  });

  const lists = wrapper.findAll('.dictionary-option-choice-list');
  expect(lists).toHaveLength(2);
  expect(lists[1].classes()).toContain('is-nested');
  expect(lists[1].text()).toContain('二级分类');
});

it('forwards a double-click from a nested dictionary child to the dialog owner', async () => {
  const wrapper = mount(DictionaryOptionDialogChoiceList, {
    props: {
      nodes: [
        {
          value: 'parent',
          title: '一级分类',
          children: [{ value: 'child', title: '二级分类' }],
        },
      ],
      selectedCodes: [],
    },
  });

  await wrapper.findAll('button')[1]!.trigger('dblclick');
  expect(wrapper.emitted('double-click')).toEqual([['child']]);
});

it('uses one picker for searchable dropdowns and commits dictionary codes immediately', async () => {
  const wrapper = mountPicker({ mode: 'dropdown', value: 'enabled', items: items.slice(0, 2) });
  const select = wrapper.findComponent({ name: 'UiSelect' });

  expect(select.props('value')).toBe('enabled');
  select.vm.$emit('search', '停用');
  await wrapper.vm.$nextTick();
  expect(select.props('options')).toEqual([{ label: '停用', value: 'disabled', disabled: true }]);

  select.vm.$emit('update:value', 'enabled');
  expect(wrapper.emitted('update:value')).toEqual([['enabled']]);
  expect(wrapper.emitted('select')).toEqual([['enabled']]);
});

it('keeps a selected dictionary title visible until the user edits the dropdown search draft', async () => {
  const wrapper = mountPicker({ mode: 'dropdown', value: 'enabled', items: items.slice(0, 2) });
  const select = wrapper.findComponent({ name: 'UiSelect' });

  expect(select.props('searchValue')).toBe('启用');
  // Ant Design emits this as focus changes from the selected label into its search input.
  select.vm.$emit('update:searchValue', '');
  await wrapper.vm.$nextTick();
  expect(select.props('searchValue')).toBe('启用');

  select.vm.$emit('update:searchValue', '停用');
  await wrapper.vm.$nextTick();
  expect(select.props('searchValue')).toBe('停用');
  expect(select.props('options')).toEqual([{ label: '停用', value: 'disabled', disabled: true }]);
});

it('clears a selected dictionary code when an explicitly edited empty search draft loses focus', async () => {
  const wrapper = mountPicker({ mode: 'dropdown', value: 'enabled', items: items.slice(0, 2) });
  const select = wrapper.findComponent({ name: 'UiSelect' });

  await wrapper.get('input').setValue('');
  select.vm.$emit('update:searchValue', '');
  await wrapper.vm.$nextTick();
  expect(select.props('value')).toBeUndefined();
  await wrapper.trigger('focusout', { relatedTarget: document.body });
  await wrapper.vm.$nextTick();

  expect(wrapper.emitted('update:value')).toEqual([[undefined]]);
  expect(wrapper.emitted('select')).toEqual([[undefined]]);
});

it('retains a nonempty edited draft when Select clears its transient search text on blur', async () => {
  const wrapper = mountPicker({ mode: 'dropdown', value: 'enabled', items: items.slice(0, 2) });
  const select = wrapper.findComponent({ name: 'UiSelect' });

  await wrapper.get('input').setValue('启用 22');
  select.vm.$emit('update:searchValue', '启用 22');
  // This empty event is Ant Design cleanup during focus departure, not a user deletion.
  select.vm.$emit('update:searchValue', '');
  await wrapper.trigger('focusout', { relatedTarget: document.body });
  await wrapper.vm.$nextTick();

  expect(wrapper.emitted('update:value')).toBeUndefined();
  expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
    { valid: false, status: 'unmatched', message: '未找到匹配的字典项' },
  ]);
});

it('opens dictionary browsing when a dropdown text area is double-clicked', async () => {
  const wrapper = mountPicker({ mode: 'dropdown', value: 'enabled', items: items.slice(0, 2) });
  wrapper.findComponent({ name: 'UiSelect' }).vm.$emit('dblclick', new MouseEvent('dblclick'));
  await wrapper.vm.$nextTick();

  expect(wrapper.findComponent({ name: 'DictionaryOptionDialog' }).props('open')).toBe(true);
});

it('matches a pasted dictionary title on dropdown blur and commits its code', async () => {
  const wrapper = mountPicker({ mode: 'dropdown', items: items.slice(0, 1) });
  const select = wrapper.findComponent({ name: 'UiSelect' });

  select.vm.$emit('update:searchValue', '启用');
  await wrapper.vm.$nextTick();
  expect(select.props('searchValue')).toBe('启用');

  await wrapper.trigger('focusout', { relatedTarget: document.body });
  await wrapper.vm.$nextTick();
  expect(wrapper.emitted('update:value')).toEqual([['enabled']]);
  expect(wrapper.emitted('select')).toEqual([['enabled']]);
  expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([{ valid: true, status: 'ready' }]);
});

it('forwards a real tree-select focus departure into dictionary draft completion', async () => {
  const wrapper = mount(DictionaryPicker, {
    props: { mode: 'dropdown', items: items.slice(2) },
    attachTo: document.body,
  });
  try {
    const input = wrapper.get('input');
    await input.setValue('二级分类');
    await input.trigger('focusout', { relatedTarget: document.body });
    await wrapper.vm.$nextTick();

    expect(wrapper.emitted('update:value')).toEqual([['child']]);
  } finally {
    wrapper.unmount();
  }
});

it('keeps the dictionary code unchanged and reports a UI-level error when a blurred draft cannot match', async () => {
  const wrapper = mountPicker({ mode: 'dropdown', value: 'enabled', items: items.slice(0, 1) });
  const select = wrapper.findComponent({ name: 'UiSelect' });

  select.vm.$emit('search', '未维护的字典值');
  // Select clears its display-search during blur; that browser cleanup must not erase the draft
  // before its matching result is reported.
  select.vm.$emit('search', '');
  await wrapper.trigger('focusout', { relatedTarget: document.body });
  await wrapper.vm.$nextTick();

  expect(wrapper.emitted('update:value')).toBeUndefined();
  expect(select.props('unmatched')).toBe(true);
  expect(wrapper.emitted('validity-change')?.at(-1)).toEqual([
    { valid: false, status: 'unmatched', message: '未找到匹配的字典项' },
  ]);
  expect(wrapper.get('.dictionary-picker__unmatched-draft').text()).toBe('未维护的字典值');
});

it('sends multi-value free text to the searchable dialog instead of inventing a delimiter grammar', async () => {
  const wrapper = mountPicker({ mode: 'dropdown', selectionMode: 'MULTIPLE', items: items.slice(0, 1) });
  const select = wrapper.findComponent({ name: 'UiSelect' });

  select.vm.$emit('search', '启用');
  await wrapper.trigger('focusout', { relatedTarget: document.body });
  await wrapper.vm.$nextTick();

  expect(wrapper.findComponent({ name: 'DictionaryOptionDialog' }).props('open')).toBe(true);
  expect(wrapper.emitted('update:value')).toBeUndefined();
});

it('delegates compact browsing to the shared dialog while retaining its code-only confirmation boundary', async () => {
  const wrapper = mountPicker({ value: 'enabled', items: items.slice(2) });
  const compactInput = wrapper.findComponent({ name: 'ObjectPickerInput' });

  compactInput.vm.$emit('browse', '二级');
  await wrapper.vm.$nextTick();
  const dialog = wrapper.findComponent({ name: 'DictionaryOptionDialog' });
  expect(dialog.props('open')).toBe(true);
  expect(dialog.props('initialKeyword')).toBe('二级');
  expect(dialog.props('value')).toBe('enabled');

  dialog.vm.$emit('cancel');
  dialog.vm.$emit('update:open', false);
  await wrapper.vm.$nextTick();
  expect(wrapper.emitted('update:value')).toBeUndefined();

  compactInput.vm.$emit('browse', '');
  await wrapper.vm.$nextTick();
  const reopened = wrapper.findComponent({ name: 'DictionaryOptionDialog' });
  reopened.vm.$emit('update:value', 'child');
  reopened.vm.$emit('confirm', 'child');
  reopened.vm.$emit('update:open', false);
  await wrapper.vm.$nextTick();
  expect(wrapper.emitted('update:value')).toEqual([['child']]);
  expect(wrapper.emitted('confirm')).toEqual([['child']]);
});

it('confirms a single dictionary picker choice immediately on double-click', async () => {
  const wrapper = mountPicker({ items: items.slice(0, 2) });
  const compactInput = wrapper.findComponent({ name: 'ObjectPickerInput' });
  compactInput.vm.$emit('browse', '');
  await wrapper.vm.$nextTick();

  const dialog = wrapper.findComponent({ name: 'DictionaryOptionDialog' });
  dialog.vm.$emit('update:value', 'enabled');
  dialog.vm.$emit('confirm', 'enabled');
  dialog.vm.$emit('update:open', false);
  await wrapper.vm.$nextTick();

  expect(wrapper.emitted('update:value')).toEqual([['enabled']]);
  expect(wrapper.emitted('select')).toEqual([['enabled']]);
  expect(wrapper.emitted('confirm')).toEqual([['enabled']]);
  expect(wrapper.findComponent({ name: 'DictionaryOptionDialog' }).props('open')).toBe(false);
});

it('keeps the external code unchanged on cancel and commits the draft code only after confirmation', async () => {
  const wrapper = mountDialog({ value: 'enabled', items: items.slice(0, 2) });
  const choices = wrapper.findComponent({ name: 'DictionaryOptionDialogChoiceList' });

  choices.vm.$emit('update:selectedCodes', ['disabled']);
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('cancel');
  await wrapper.vm.$nextTick();

  expect(wrapper.emitted('update:value')).toBeUndefined();
  expect(wrapper.emitted('cancel')).toEqual([[]]);
  expect(wrapper.emitted('update:open')).toEqual([[false]]);

  await wrapper.setProps({ open: false });
  await wrapper.setProps({ open: true });
  expect(wrapper.findComponent({ name: 'DictionaryOptionDialogChoiceList' }).props('selectedCodes')).toEqual([
    'enabled',
  ]);

  wrapper
    .findComponent({ name: 'DictionaryOptionDialogChoiceList' })
    .vm.$emit('update:selectedCodes', ['enabled']);
  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
  await wrapper.vm.$nextTick();

  expect(wrapper.emitted('update:value')).toEqual([['enabled']]);
  expect(wrapper.emitted('confirm')).toEqual([['enabled']]);
});

it('clears the dialog draft and emits an empty multiple code list only when confirmed', async () => {
  const wrapper = mountDialog({ items: items.slice(0, 2), value: ['enabled'], selectionMode: 'MULTIPLE' });

  wrapper.findComponent({ name: 'UiButton' }).vm.$emit('click');
  await wrapper.vm.$nextTick();
  expect(wrapper.emitted('clear')).toEqual([[]]);
  expect(wrapper.emitted('update:value')).toBeUndefined();
  expect(wrapper.findComponent({ name: 'DictionaryOptionDialogChoiceList' }).props('selectedCodes')).toEqual(
    [],
  );

  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
  await wrapper.vm.$nextTick();
  expect(wrapper.emitted('update:value')).toEqual([[[]]]);
});

it('filters the direct candidate list by title or code without truncation', async () => {
  const options = Array.from({ length: 26 }, (_, index) => ({
    code: `code-${index + 1}`,
    title: `候选 ${index + 1}`,
    enabled: true,
  }));
  const wrapper = mountDialog({ items: options });

  wrapper.findComponent({ name: 'UiSearchInput' }).vm.$emit('update:value', 'code-25');
  await wrapper.vm.$nextTick();

  expect(wrapper.findComponent({ name: 'DictionaryOptionDialogChoiceList' }).props('nodes')).toEqual([
    { value: 'code-25', title: '候选 25', disabled: false },
  ]);
});

it('returns multiple dictionary codes while refusing disabled options as new selections', async () => {
  const wrapper = mountDialog({ items: items.slice(0, 2), selectionMode: 'MULTIPLE' });
  const choices = wrapper.findComponent({ name: 'DictionaryOptionDialogChoiceList' });

  choices.vm.$emit('update:selectedCodes', ['enabled', 'disabled']);
  await wrapper.vm.$nextTick();
  expect(choices.props('selectedCodes')).toEqual(['enabled']);

  wrapper.findComponent({ name: 'UiModal' }).vm.$emit('confirm');
  await wrapper.vm.$nextTick();
  expect(wrapper.emitted('update:value')).toEqual([[['enabled']]]);
});

it('preserves parentCode hierarchy and keeps the matched child beneath its parent during search', async () => {
  const wrapper = mountDialog({ items: items.slice(2) });
  const choices = wrapper.findComponent({ name: 'DictionaryOptionDialogChoiceList' });

  expect(choices.props('nodes')).toEqual([
    {
      value: 'parent',
      title: '一级分类',
      disabled: false,
      children: [{ value: 'child', title: '二级分类', disabled: false }],
    },
  ]);

  wrapper.findComponent({ name: 'UiSearchInput' }).vm.$emit('search', '二级');
  await wrapper.vm.$nextTick();
  expect(choices.props('nodes')).toEqual([
    {
      value: 'parent',
      title: '一级分类',
      disabled: false,
      children: [{ value: 'child', title: '二级分类', disabled: false }],
    },
  ]);
});

it('radio group returns only an enabled dictionary code and keeps maxOptions advisory for the host', async () => {
  const wrapper = shallowMount(DictionaryRadioGroup, {
    props: { items: items.slice(0, 2), maxOptions: '12' },
    global: {
      stubs: {
        UiRadioGroup: {
          name: 'UiRadioGroup',
          props: ['options', 'dataMaxOptions'],
          emits: ['update:value'],
          template: '<div />',
        },
      },
    },
  });
  const radio = wrapper.findComponent({ name: 'UiRadioGroup' });

  expect(radio.props('options')).toEqual([
    { label: '启用', value: 'enabled', disabled: false },
    { label: '停用', value: 'disabled', disabled: true },
  ]);
  radio.vm.$emit('update:value', 'disabled');
  radio.vm.$emit('update:value', 'enabled');
  await wrapper.vm.$nextTick();

  expect(wrapper.emitted('update:value')).toEqual([['enabled']]);
});
