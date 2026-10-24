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
import { MANAGEMENT_WORKSPACE_LAYOUT } from '@/platform-components/managementWorkspaceLayout';
import '@/styles.css';
import '@muyun/vue-ui-antdv/styles.css';

it.each([
  { width: 1004, system: false },
  { width: 600, system: false },
  { width: 1280, system: true },
  { width: 1004, system: true },
  { width: 600, system: true },
  { width: 1004, system: true, scoped: true },
  { width: 600, system: false, scoped: true },
  { width: 1004, system: true, dynamic: true },
  { width: 600, system: false, dynamic: true },
])('fits $width / system=$system / scoped=$scoped', async ({ width, system, scoped, dynamic }) => {
  await page.viewport(width, 804);
  const target: CodeTarget = {
    moduleAlias: 'demo.purchase',
    moduleTitle: '采购申请',
    entityAlias: 'purchase',
    title: '采购申请',
    source: dynamic ? 'DYNAMIC' : 'STATIC',
    fields: [
      {
        fieldName: 'documentNo',
        title: '申请单号',
        role: dynamic ? undefined : 'PRIMARY',
        trigger: dynamic ? undefined : 'SAVE',
      },
    ],
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
  let configured = false;
  const paths: string[] = [];
  const context = {
    moduleAlias: 'platform.code_rule',
    can: () => true,
    http: {
      async request(options: HttpRequestOptions) {
        paths.push(options.path);
        if (options.path.endsWith('/saveTree')) {
          const saved = options.body as typeof rule;
          if (saved.enabled) configured = true;
          return { ...saved, id: 'rule', version: 1 };
        }
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
        if (options.path.includes('/targets'))
          return [
            {
              ...target,
              fields: target.fields.map((field) =>
                configured ? { ...field, role: 'NORMAL', trigger: 'SAVE' } : field,
              ),
            },
          ];
        if (options.path.includes('/rules?')) return dynamic ? [] : [rule];
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
                h(ModuleContextProvider, { context }, () =>
                  h(CodeConfigurationView, {
                    embedded: true,
                    ...(scoped ? { moduleAlias: target.moduleAlias, moduleTitle: target.moduleTitle } : {}),
                  }),
                ),
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
    if (scoped) {
      expect(wrapper.findAllComponents({ name: 'ManagementExplorerColumn' })).toHaveLength(system ? 1 : 0);
      expect(wrapper.findComponent({ name: 'RecordListExplorer' }).exists()).toBe(system);
    } else {
      if (width === 600) {
        await expect
          .poll(() => wrapper.findAll('.management-explorer-column--collapsed').length)
          .toBe(system ? 2 : 1);
        await page.getByRole('button', { name: '展开所属模块', exact: true }).click();
      }
      await page.getByText('采购申请', { exact: true }).click();
      if (width === 600) await page.getByRole('button', { name: '收起所属模块', exact: true }).click();
    }
    await flushPromises();
    expect(wrapper.find('.code-config > header').exists()).toBe(false);
    const workspace = wrapper.get<HTMLElement>('.management-workspace').element;
    const host = workspace.getBoundingClientRect();
    expect(
      wrapper.get('.code-config__overview').element.getBoundingClientRect().width,
    ).toBeGreaterThanOrEqual(
      Math.min(
        MANAGEMENT_WORKSPACE_LAYOUT.detailMinWidth,
        host.width - (workspace.querySelector('.management-explorer-column--collapsed') ? 48 : 0),
      ),
    );
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width + 1);
    await expect.poll(() => workspace.scrollWidth).toBeLessThanOrEqual(workspace.clientWidth + 1);
    await page.getByRole('button', { name: '刷新：编码对象', exact: true }).click();
    await flushPromises();
    const overview = wrapper.get('.code-config__overview').element.getBoundingClientRect();
    expect(overview.right).toBeLessThanOrEqual(host.right + 1);
    expect(overview.bottom).toBeLessThanOrEqual(host.bottom + 1);
    const create = page.getByRole('button', { name: '新增规则', exact: true });
    await expect.element(create).toBeVisible();
    const createBounds = wrapper.get('button[aria-label="新增规则"]').element.getBoundingClientRect();
    expect(createBounds.right).toBeLessThanOrEqual(host.right + 1);
    expect(createBounds.bottom).toBeLessThanOrEqual(host.bottom + 1);
    if (dynamic) {
      await page.getByRole('button', { name: '新增规则', exact: true }).click();
    } else {
      await page.getByRole('button', { name: '字段设置说明', exact: true }).click();
      await expect
        .element(page.getByText('字段角色和生成时机由业务模型预设。', { exact: true }))
        .toBeVisible();
      await userEvent.keyboard('{Escape}');
      await page.getByRole('button', { name: '申请单号', exact: true }).click();
    }
    await flushPromises();
    const save = page.getByRole('button', { name: '保存规则', exact: true });
    await expect.element(save).toBeVisible();
    const editorContent = wrapper.get('.code-config__editor-content').element.getBoundingClientRect();
    const format = wrapper.get('.code-config__editor-main').element.getBoundingClientRect();
    const basicFields = wrapper.findAll('.code-config__basic > label');
    expect(basicFields[0]!.text()).toContain('规则名称');
    expect(basicFields[1]!.text()).toContain('编号方式');
    const titleBounds = basicFields[0]!.element.getBoundingClientRect();
    const modeBounds = basicFields[1]!.element.getBoundingClientRect();
    expect(modeBounds.top).toBeGreaterThan(titleBounds.bottom);
    expect(modeBounds.left).toBe(titleBounds.left);
    expect(modeBounds.width).toBe(titleBounds.width);
    const trialPanel = wrapper.get('.code-config__trial').element.getBoundingClientRect();
    expect(trialPanel.right).toBeLessThanOrEqual(editorContent.right + 1);
    if (editorContent.width > 720) {
      expect(trialPanel.left).toBeGreaterThanOrEqual(format.right);
      expect(Math.abs(trialPanel.top - format.top)).toBeLessThan(1);
    } else {
      expect(trialPanel.top).toBeGreaterThanOrEqual(format.bottom);
    }
    expect(wrapper.find('select:disabled').exists()).toBe(false);
    if (dynamic) {
      expect(wrapper.find('.code-config__draft-field-settings').exists()).toBe(false);
      await save.click();
      expect(configured).toBe(false);
      await page.getByRole('button', { name: '关闭', exact: true }).click();
      const toggle = page.getByRole('switch', { name: '启用规则：申请单号', exact: true });
      const toggleWidth = wrapper.get('[role="switch"]').element.getBoundingClientRect().width;
      expect(toggleWidth).toBeGreaterThanOrEqual(28);
      expect(toggleWidth).toBeLessThanOrEqual(48);
      for (const column of wrapper.findAll(
        '.management-explorer-column:not(.management-explorer-column--collapsed)',
      )) {
        await column.get('button[aria-label^="收起"]').trigger('click');
      }
      await page.getByRole('button', { name: '编辑适用配置：申请单号', exact: true }).click();
      expect(wrapper.find('.code-config__editor').exists()).toBe(false);
      const usage = wrapper.get('.code-config__rules-table tbody tr');
      const dateInput = usage.get<HTMLInputElement>('.ui-date-picker input').element;
      dateInput.scrollIntoView({ block: 'nearest', inline: 'center' });
      await page.elementLocator(dateInput).click();
      await page.elementLocator(dateInput).fill('2026-01-01 00:00');
      await userEvent.keyboard('{Enter}');
      const row = wrapper.get('.code-config__rules-table tbody tr').element;
      expect(row.querySelectorAll('.ui-date-picker input')).toHaveLength(2);
      expect(wrapper.find('.code-config__usage').exists()).toBe(false);
      expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width + 1);
      await page.getByRole('button', { name: '保存', exact: true }).click();
      await expect.element(page.getByText('适用配置已保存。', { exact: true })).toBeVisible();
      expect(wrapper.find('.code-config__rules-table .ui-date-picker input').exists()).toBe(false);
      await toggle.click();
      await expect.poll(() => configured).toBe(true);
      await toggle.click();
      await expect.element(page.getByText('规则已停用，已有编码保持不变。', { exact: true })).toBeVisible();
      expect(paths.some((path) => path.endsWith('/binding'))).toBe(false);
      expect(wrapper.find('.code-config > header').exists()).toBe(false);
      return;
    }
    const policy = wrapper.get<HTMLDetailsElement>('.code-config__policy');
    expect(policy.element.open).toBe(false);
    expect(policy.get('summary').text()).toContain('旧号可复用');
    expect(policy.find('.ui-date-picker input').exists()).toBe(false);
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
    content.scrollTop = 0;
    const anchoredTop = wrapper.get('.code-config__trial').element.getBoundingClientRect().top;
    const titleTop = basicFields[0]!.element.getBoundingClientRect().top;
    content.scrollTop = content.scrollHeight;
    if (editorContent.width > 720) {
      await expect
        .poll(() =>
          Math.abs(wrapper.get('.code-config__trial').element.getBoundingClientRect().top - anchoredTop),
        )
        .toBeLessThan(1);
      expect(basicFields[0]!.element.getBoundingClientRect().top).toBeLessThan(titleTop);
    }
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
    await page.getByRole('spinbutton', { name: '试算流水值', exact: true }).fill('-1');
    const trialError = page.getByText('试算流水值须为非负整数。', { exact: true });
    await expect.element(trialError).toBeVisible();
    expect(document.activeElement).toBe(wrapper.get('.code-config__trial input[type="number"]').element);
    await expect
      .poll(() => wrapper.get('.code-config__trial-error').element.getBoundingClientRect().top)
      .toBeGreaterThanOrEqual(content.getBoundingClientRect().top - 1);
    await expect
      .poll(() => wrapper.get('.code-config__trial-error').element.getBoundingClientRect().bottom)
      .toBeLessThanOrEqual(content.getBoundingClientRect().bottom + 1);
    await page.getByRole('spinbutton', { name: '试算流水值', exact: true }).fill('');
    expect(wrapper.find('.code-config__trial-error').exists()).toBe(false);
    expect(wrapper.find('button[aria-label="草稿试算"]').exists()).toBe(false);
    expect(wrapper.get('.code-config__trial').element.tagName).toBe('ASIDE');
    expect(wrapper.find('.code-config__trial-extra[open]').exists()).toBe(false);
    await expect
      .poll(() => wrapper.get('.code-config__trial').element.getBoundingClientRect().top)
      .toBeGreaterThanOrEqual(content.getBoundingClientRect().top - 1);
    await expect.element(save).toBeVisible();
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width + 1);
    await page.getByRole('button', { name: '刷新试算', exact: true }).click();
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
