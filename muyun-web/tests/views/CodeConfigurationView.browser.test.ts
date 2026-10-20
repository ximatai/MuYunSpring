import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { expect, it } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { UiThemeProvider, UiSidePanelHost } from '@muyun/vue-ui-antdv';
import { ModuleContextProvider, type ModuleContext, type HttpRequestOptions } from '@muyun/web-core';
import { providePageLayout } from '@/platform-components/pageLayoutContext';
import { provideCurrentUserContext } from '@/platform-admin-runtime/currentUserContext';
import type { CurrentUser } from '@muyun/web-contracts';
import CodeConfigurationView from '@/views/CodeConfigurationView.vue';
import { newCodeRule, newCodeSegment, type CodeTarget } from '@/views/codeConfiguration';
import '@/styles.css';
import '@muyun/vue-ui-antdv/styles.css';

it.each([
  { width: 1004, system: false },
  { width: 600, system: false },
  { width: 1280, system: true },
  { width: 1004, system: true },
  { width: 600, system: true },
])('keeps code operations visible at viewport $width with system=$system', async ({ width, system }) => {
  await page.viewport(width, 804);
  const target: CodeTarget = {
    moduleAlias: 'demo.purchase',
    moduleTitle: '采购申请',
    entityAlias: 'purchase',
    title: '采购申请',
    source: 'STATIC',
    fields: [{ fieldName: 'documentNo', title: '申请单号', role: 'PRIMARY', trigger: 'SAVE' }],
    sources: [],
  };
  const rule = { ...newCodeRule(target, target.fields[0]!), id: 'rule', allowRecycle: true };
  rule.sequencePolicy!.startValue = 20;
  rule.segments = [
    {
      ...newCodeSegment('VALUE_MAPPING'),
      mappings: [{ sourceValue: 'a', targetValue: 'A', defaultMapping: false, enabled: true }],
    },
    { ...newCodeSegment('SEQUENCE'), length: 4 },
  ];
  const context = {
    moduleAlias: 'platform.code_rule',
    can: () => true,
    http: {
      async request(options: HttpRequestOptions) {
        if (options.path.endsWith('/iam.tenant/reference-context'))
          return { moduleAlias: 'iam.tenant', capabilities: [], abilities: ['crud'], actions: [] };
        if (options.path.includes('iam.tenant'))
          return {
            records: [{ id: 'tenant-a', title: '演示租户' }],
            total: 1,
            pageNum: 1,
            pageSize: 200,
            pages: 1,
            totalKnown: true,
          };
        if (options.path.includes('/targets')) return [target];
        if (options.path.includes('/rules?')) return [rule];
        if (options.path.endsWith('/preview')) return { value: 'PREVIEW-0021', segments: [] };
        return undefined;
      },
    },
  } as unknown as ModuleContext<unknown>;
  const wrapper = mount(
    defineComponent({
      setup() {
        providePageLayout('workspace');
        provideCurrentUserContext(ref({ tenantId: 'tenant-a', system } as CurrentUser));
        return () =>
          h('div', { style: 'height:700px' }, [
            h(UiThemeProvider, {}, () =>
              h(UiSidePanelHost, {}, () =>
                h(ModuleContextProvider, { context }, () => h(CodeConfigurationView, { embedded: true })),
              ),
            ),
          ]);
      },
    }),
    { attachTo: document.body },
  );
  try {
    await flushPromises();
    expect(wrapper.findComponent({ name: 'RecordDetailDrawer' }).exists()).toBe(false);
    await page.getByText('采购申请', { exact: true }).click();
    await flushPromises();
    const workspace = wrapper.get('.management-workspace').element;
    const host = workspace.getBoundingClientRect();
    const overview = wrapper.get('.code-config__overview').element.getBoundingClientRect();
    expect(overview.right).toBeLessThanOrEqual(host.right + 1);
    expect(overview.bottom).toBeLessThanOrEqual(host.bottom + 1);
    const create = page.getByRole('button', { name: '新增规则', exact: true });
    await expect.element(create).toBeVisible();
    const createBounds = wrapper
      .findAll('button')
      .find((button) => button.text() === '新增规则')!
      .element.getBoundingClientRect();
    expect(createBounds.right).toBeLessThanOrEqual(host.right + 1);
    expect(createBounds.bottom).toBeLessThanOrEqual(host.bottom + 1);
    await page.getByRole('button', { name: '字段接入说明', exact: true }).click();
    await expect.element(page.getByText('字段角色和生成时机由业务模型预设。', { exact: true })).toBeVisible();
    await userEvent.keyboard('{Escape}');
    await page.getByRole('button', { name: /申请单号.*未启用/ }).click();
    await flushPromises();
    const save = page.getByRole('button', { name: '保存规则', exact: true });
    await expect.element(save).toBeVisible();
    expect(wrapper.find('select:disabled').exists()).toBe(false);
    const policy = wrapper.get<HTMLDetailsElement>('.code-config__policy');
    expect(policy.element.open).toBe(false);
    expect(policy.get('summary').text()).toContain('旧号可复用');
    expect(policy.find('input[type="datetime-local"]').isVisible()).toBe(false);
    const sequence = wrapper.get<HTMLDetailsElement>('.code-format__sequence-advanced');
    expect(sequence.element.open).toBe(false);
    expect(sequence.get('summary').text()).toContain('20');
    await page.elementLocator(sequence.get('summary').element).click();
    await page.getByRole('spinbutton', { name: '起始值', exact: true }).fill('21');
    await page.elementLocator(sequence.get('summary').element).click();
    expect(sequence.findAll('input')[0]!.element.value).toBe('21');
    const editor = document.querySelector('.ant-drawer-content')!.getBoundingClientRect();

    const saveButton = wrapper
      .findAll('button')
      .find((button) => button.text().replace(/\s+/g, '') === '保存规则')!.element;
    const before = saveButton.getBoundingClientRect();
    const advanced = wrapper.findAll<HTMLDetailsElement>('.code-format__advanced');
    expect(advanced.every((details) => !details.element.open)).toBe(true);
    await page.getByRole('spinbutton', { name: '流水长度', exact: true }).fill('6');
    await expect
      .element(page.elementLocator(advanced[1]!.get('summary').element))
      .toHaveTextContent('长度覆盖 4 位');
    expect(advanced[1]!.element.open).toBe(false);
    for (let index = 0; index < advanced.length; index++) {
      await page.elementLocator(advanced[index]!.get('summary').element).click();
    }
    const content = document.querySelector('.ant-drawer-content .record-detail-layout-content')!;
    await expect.poll(() => content.clientHeight).toBeLessThan(700);
    expect(content.scrollHeight).toBeGreaterThan(content.clientHeight);
    content.scrollTop = content.scrollHeight;
    await expect.element(save).toBeVisible();
    expect(saveButton.getBoundingClientRect().top).toBe(before.top);
    expect(before.right).toBeLessThanOrEqual(editor.right);
    const mapping = wrapper.get('.code-format__mapping-row').element;
    const mappingBounds = mapping.getBoundingClientRect();
    for (const control of mapping.querySelectorAll('input,button,label')) {
      const bounds = control.getBoundingClientRect();
      expect(bounds.left).toBeGreaterThanOrEqual(mappingBounds.left - 1);
      expect(bounds.right).toBeLessThanOrEqual(mappingBounds.right + 1);
    }
    const label = wrapper.get('.code-format__mapping-default').element;
    const text = label.lastElementChild!.getBoundingClientRect();
    expect(text.width).toBeGreaterThan(40);
    expect(text.height).toBeLessThan(30);
    const mark = label.firstElementChild!.getBoundingClientRect();
    expect(Math.abs(mark.top + mark.height / 2 - text.top - text.height / 2)).toBeLessThan(4);
    for (const checkbox of wrapper.findAll('.code-format__basis')) {
      const mark = checkbox.element.firstElementChild!.getBoundingClientRect();
      const text = checkbox.element.lastElementChild!.getBoundingClientRect();
      expect(Math.abs(mark.top + mark.height / 2 - text.top - text.height / 2)).toBeLessThan(4);
    }
    await page.getByRole('button', { name: '草稿试算', exact: true }).click();
    expect(wrapper.get<HTMLDetailsElement>('.code-config__trial').element.open).toBe(true);
    await expect
      .poll(() => wrapper.get('.code-config__trial').element.getBoundingClientRect().top)
      .toBeGreaterThanOrEqual(content.getBoundingClientRect().top - 1);
    await expect.element(save).toBeVisible();
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width + 1);
    await page.getByRole('button', { name: /^试\s*算$/ }).click();
    await expect.element(page.getByText('PREVIEW-0021', { exact: true })).toBeVisible();
    await expect
      .poll(() => wrapper.get('output').element.getBoundingClientRect().bottom)
      .toBeLessThanOrEqual(content.getBoundingClientRect().bottom + 1);
    expect(wrapper.get('output').element.getBoundingClientRect().top).toBeGreaterThanOrEqual(
      content.getBoundingClientRect().top,
    );
    await expect.element(save).toBeVisible();
  } finally {
    wrapper.unmount();
  }
});
