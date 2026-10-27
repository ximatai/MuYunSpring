import { mount } from '@vue/test-utils';
import { h } from 'vue';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import RecordFormGrid from '@/platform-components/RecordFormGrid.vue';
import UiSelect from '@/vue-ui-antdv/components/UiSelect.vue';
import UiInput from '@/vue-ui-antdv/components/UiInput.vue';
import '@/styles.css';

it('keeps long searchable selections within their form column', async () => {
  await page.viewport(992, 814);
  const wrapper = mount(RecordFormGrid, {
    attrs: { style: 'width: 649px' },
    slots: {
      default: () => [
        h('label', [
          '目标模块',
          h(UiSelect, {
            value: 'purchase',
            showSearch: true,
            style: 'width: 100%',
            options: [{ value: 'purchase', label: '验收·采购申请 · education.accept_purchase_0906' }],
          }),
        ]),
        h('label', ['显示名称', h(UiInput, { value: '验收·采购申请' })]),
      ],
    },
    attachTo: document.body,
  });
  try {
    const column = wrapper.get('label').element.getBoundingClientRect();
    const select = wrapper.get('.ant-select').element.getBoundingClientRect();
    const neighbor = wrapper.findAll('label')[1]!.element.getBoundingClientRect();
    expect(select.width).toBeLessThanOrEqual(column.width + 1);
    expect(select.right).toBeLessThan(neighbor.left);
    await page.elementLocator(wrapper.get('.ant-select-selector').element).click();
    expect(wrapper.get('.ant-select').element.getBoundingClientRect().right).toBeLessThan(neighbor.left);
  } finally {
    wrapper.unmount();
  }
});

it.each([992, 390])('keeps source controls with their own field at viewport %s', async (width) => {
  await page.viewport(width, 814);
  const { default: RecordFormFields } = await import('@/platform-components/RecordFormFields.vue');
  const fields = new Map([
    ['code', { fieldRef: { fieldName: 'code' }, label: 'Code' }],
    [
      'title',
      {
        fieldRef: { fieldName: 'title' },
        label: 'Title',
        content: { key: 'title', policy: 'MANUAL_TRANSLATIONS' as const },
      },
    ],
    [
      'description',
      {
        fieldRef: { fieldName: 'description' },
        label: 'Description',
        uiType: 'textarea',
        columnSpan: 2 as const,
        content: { key: 'description', policy: 'AUTO_TRANSLATE' as const },
      },
    ],
  ]);
  const wrapper = mount(RecordFormGrid, {
    attrs: { style: 'width: min(680px, calc(100vw - 32px)); margin: 16px' },
    slots: {
      default: () =>
        h(RecordFormFields, {
          fields,
          record: { code: 'C001', title: 'Original title', description: 'Original description' },
          mode: 'create',
        }),
    },
    attachTo: document.body,
  });
  try {
    const hosts = wrapper.findAll('.record-form-field-host');
    const title = hosts[1]!.element.getBoundingClientRect();
    const source = wrapper.get('[data-content-field="title"]').element.getBoundingClientRect();
    const input = hosts[1]!.get('input').element.getBoundingClientRect();
    expect(source.left).toBeGreaterThanOrEqual(title.left);
    expect(source.right).toBeLessThanOrEqual(title.right + 1);
    expect(source.top).toBeGreaterThanOrEqual(input.bottom);
    expect(source.bottom).toBeLessThanOrEqual(title.bottom + 1);
    const fullRow = hosts[2]!.element.getBoundingClientRect();
    const grid = wrapper.element.getBoundingClientRect();
    expect(fullRow.width).toBeCloseTo(grid.width, 0);
    expect(fullRow.top).toBeGreaterThanOrEqual(title.bottom);
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width + 1);
  } finally {
    wrapper.unmount();
  }
});

it.each([1063, 390])('lays out duration and paired switches without overflow at %s', async (width) => {
  await page.viewport(width, 804);
  const { default: RecordFormFields } = await import('@/platform-components/RecordFormFields.vue');
  const group = {
    groupCode: 'login',
    fields: ['provider', 'age', 'login', 'binding'].map((fieldName) => ({ fieldName })),
    title: '登录与绑定',
    subtitle: '状态有效期为 1 分钟至 30 天；超期需重新核验来源状态。登录与绑定不授予业务数据权限。',
  };
  const fields = new Map<
    string,
    import('@/platform-components/recordFormFieldModel').RecordFormFieldDescriptor
  >([
    ['provider', { fieldRef: { fieldName: 'provider' }, label: '外部身份源', formGroup: group }],
    [
      'age',
      {
        fieldRef: { fieldName: 'age' },
        label: '来源状态有效期',
        formGroup: group,
        valueType: 'INTEGER',
        fieldControl: {
          alias: 'duration_seconds',
          rendererType: 'NUMBER',
          valueShape: 'SCALAR',
          properties: {},
          bindings: [],
        },
      },
    ],
    [
      'login',
      { fieldRef: { fieldName: 'login' }, label: '允许扫码登录', uiType: 'switch', formGroup: group },
    ],
    [
      'binding',
      { fieldRef: { fieldName: 'binding' }, label: '允许账号绑定', uiType: 'switch', formGroup: group },
    ],
  ]);
  const wrapper = mount(RecordFormGrid, {
    props: { surface: 'record' },
    attrs: { style: 'width: min(540px, calc(100vw - 32px)); margin: 16px' },
    slots: {
      default: () =>
        h(RecordFormFields, { fields, record: { age: 259200, login: true, binding: false }, mode: 'edit' }),
    },
    attachTo: document.body,
  });
  try {
    const hosts = wrapper.findAll('.record-form-field-host');
    const login = hosts[2]!.element.getBoundingClientRect();
    const binding = hosts[3]!.element.getBoundingClientRect();
    if (width > 900) {
      expect(Math.abs(login.top - binding.top)).toBeLessThan(1);
      expect(login.right).toBeLessThan(binding.left);
    } else expect(binding.top).toBeGreaterThanOrEqual(login.bottom);
    const duration = wrapper.get('.duration-seconds-input').element.getBoundingClientRect();
    const owner = hosts[1]!.element.getBoundingClientRect();
    expect(duration.right).toBeLessThanOrEqual(owner.right + 1);
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width + 1);
    const heading = wrapper.get('h3').element as HTMLElement;
    expect(heading.scrollWidth).toBeLessThanOrEqual(heading.clientWidth + 1);
  } finally {
    wrapper.unmount();
  }
});
