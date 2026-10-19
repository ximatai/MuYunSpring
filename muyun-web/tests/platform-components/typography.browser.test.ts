import { flushPromises, mount } from '@vue/test-utils';
import { h } from 'vue';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import { UiThemeProvider, UiButton, UiCheckbox, UiDataTable, UiTextArea } from '@muyun/vue-ui-antdv';
import { uiThemeSkins } from '@/vue-ui-antdv/theme';
import RecordFormFields from '@/platform-components/RecordFormFields.vue';
import RecordDetailPanel from '@/platform-components/RecordDetailPanel.vue';
import RecordDetailFields from '@/platform-components/RecordDetailFields.vue';
import RecordContentSectionHeading from '@/platform-components/RecordContentSectionHeading.vue';
import RecordDetailDrawer from '@/platform-components/RecordDetailDrawer.vue';
import type { RecordFormFieldDescriptor } from '@/platform-components/recordFormFieldModel';
import '@/styles.css';

const fields = new Map<string, RecordFormFieldDescriptor>([
  ['name', { fieldRef: { fieldName: 'name' }, label: '名称', valueType: 'TEXT' }],
]);
const sizeOf = (element: Element) => getComputedStyle(element).fontSize;

it('gives standalone text and undeclared headings a body scale without a theme provider', () => {
  const wrapper = mount(
    { render: () => h('div', [h('p', '普通正文'), h('h3', '未声明字号的标题')]) },
    { attachTo: document.body },
  );
  try {
    expect(sizeOf(wrapper.get('p').element)).toBe('13px');
    expect(sizeOf(wrapper.get('h3').element)).toBe('13px');
    expect(getComputedStyle(wrapper.get('p').element).lineHeight).toBe('21px');
  } finally {
    wrapper.unmount();
  }
});

it.each(uiThemeSkins)(
  'keeps forms, lists and portalled drawers on the same text roles in $id',
  async (skin) => {
    await page.viewport(1200, 900);
    const host = document.createElement('div');
    host.style.fontSize = '20px';
    document.body.append(host);
    const wrapper = mount(UiThemeProvider, {
      attachTo: host,
      global: { stubs: { transition: false, 'transition-group': false } },
      props: { theme: skin.theme, scope: 'global' },
      slots: {
        default: () => [
          h('p', { class: 'ordinary-text' }, '正文'),
          h(
            RecordDetailPanel,
            { title: '表单详情', subtitle: '次级说明' },
            {
              default: () => [
                h(RecordContentSectionHeading, { title: '基本信息' }),
                h(RecordFormFields, { record: { name: '采购单' }, fields, mode: 'edit' }),
                h(UiCheckbox, { checked: true }, () => '驱动审批状态'),
                h(UiTextArea, { value: '说明内容' }),
                h(RecordDetailFields, { record: { name: '采购单' }, fields }),
                h(UiDataTable, {
                  columns: [{ key: 'name', dataIndex: 'name', title: '名称' }],
                  rows: [{ id: 'row', name: '采购单' }],
                  pagination: false,
                }),
              ],
            },
          ),
          h(
            RecordDetailDrawer,
            { open: true, scope: 'viewport', title: '流程详情', subtitle: '抽屉说明' },
            {
              default: () => h(RecordFormFields, { record: { name: '采购单' }, fields, mode: 'edit' }),
              operation: () => h(UiButton, {}, () => '保存配置'),
            },
          ),
        ],
      },
    });
    try {
      await flushPromises();
      await expect.element(page.getByRole('button', { name: '保存配置', exact: true })).toBeVisible();
      expect(sizeOf(wrapper.get('.ordinary-text').element)).toBe('13px');
      for (const selector of [
        '.record-field-label',
        'input',
        'textarea',
        '.ant-checkbox-wrapper',
        'th',
        'td',
        'dt',
        'dd',
      ]) {
        const elements = wrapper.findAll(selector);
        expect(elements.length, selector).toBeGreaterThan(0);
        for (const element of elements) expect(sizeOf(element.element), selector).toBe('13px');
      }
      expect(sizeOf(wrapper.get('.record-detail-layout h2').element)).toBe('16px');
      expect(sizeOf(wrapper.get('.management-panel-header-subtitle').element)).toBe('12px');
      expect(sizeOf(wrapper.get('.record-content-section-heading h3').element)).toBe('14px');
      const drawer = document.querySelector('.record-detail-layout--drawer')!;
      expect(sizeOf(drawer.querySelector('h2')!)).toBe('16px');
      expect(getComputedStyle(drawer.querySelector('h2')!).fontWeight).toBe('700');
      const muted = skin.theme.support.textMuted.match(/[a-f\d]{2}/gi)!.map((value) => parseInt(value, 16));
      expect(getComputedStyle(wrapper.get('.management-panel-header-subtitle').element).color).toBe(
        `rgb(${muted.join(', ')})`,
      );
      expect(getComputedStyle(drawer.querySelector('.record-detail-layout-title-copy p')!).color).toBe(
        `rgb(${muted.join(', ')})`,
      );
      expect(getComputedStyle(drawer.querySelector('h2')!).lineHeight).toBe('22px');
      expect(getComputedStyle(drawer.querySelector('.record-detail-layout-title-copy p')!).lineHeight).toBe(
        '18px',
      );
      expect(sizeOf(drawer.querySelector('input')!)).toBe('13px');
      expect(sizeOf(drawer.querySelector('button')!)).toBe('13px');
    } finally {
      wrapper.unmount();
      host.remove();
    }
  },
);

it('isolates local typography from a larger consumer host without changing sibling text', () => {
  const wrapper = mount(
    {
      render: () =>
        h('div', { style: 'font-size:20px' }, [
          h('p', { class: 'outside' }, '宿主文字'),
          h(UiThemeProvider, { theme: uiThemeSkins[2]!.theme }, () => [
            h('p', { class: 'inside' }, '平台正文'),
            h(RecordDetailPanel, { title: '局部主题', subtitle: '局部说明' }),
          ]),
        ]),
    },
    { attachTo: document.body },
  );
  try {
    expect(sizeOf(wrapper.get('.inside').element)).toBe('13px');
    expect(sizeOf(wrapper.get('.outside').element)).toBe('20px');
    const muted = uiThemeSkins[2]!.theme.support.textMuted
      .match(/[a-f\d]{2}/gi)!
      .map((value) => parseInt(value, 16));
    expect(getComputedStyle(wrapper.get('.management-panel-header-subtitle').element).color).toBe(
      `rgb(${muted.join(', ')})`,
    );
    expect(getComputedStyle(wrapper.get('.inside').element).color).not.toBe(
      getComputedStyle(wrapper.get('.outside').element).color,
    );
  } finally {
    wrapper.unmount();
  }
});
