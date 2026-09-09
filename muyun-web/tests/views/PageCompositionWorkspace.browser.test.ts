import { defineComponent, h, KeepAlive, nextTick, ref } from 'vue';
import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import { configureModuleContext, type HttpClient } from '@/web-core';
import { providePageLayout } from '@/platform-components/pageLayoutContext';
import PageCompositionWorkspace from '@/views/PageCompositionWorkspace.vue';
import '@/styles.css';
import 'ant-design-vue/dist/reset.css';

// Real workspace + real UI adapters. Only transport is replaced, so height propagation,
// overflow ownership, hit testing and the property drawer run in Chromium.
it.each([1440, 980])(
  'keeps long composer panels scrollable and node configuration stable at %ipx',
  async (width) => {
    await page.viewport(width, 814);
    configureModuleContext({ http: layoutHttp() });
    const wrapper = mount(
      defineComponent({
        setup() {
          providePageLayout('workspace');
          return () =>
            h('div', { style: 'height: 650px; margin: 150px 10px 0;' }, [
              h(PageCompositionWorkspace, { moduleAlias: 'education.layout', moduleTitle: '布局验收' }),
            ]);
        },
      }),
      { attachTo: document.body },
    );
    try {
      await expect.element(page.getByRole('button', { name: '发布草稿', exact: true })).toBeEnabled();
      const lastField = wrapper.get('[data-ui-tree-key="ui:field:list:field-59"]').element;
      lastField.scrollIntoView({ block: 'end' });
      await page.elementLocator(lastField).dblClick();
      await expect.element(page.getByRole('textbox', { name: '展示标题', exact: true })).toBeVisible();
      await page.getByRole('button', { name: '关闭', exact: true }).click();
      const panels = wrapper.findAll('.record-explorer-panel');
      const root = wrapper.get('.management-workspace').element;
      expect(root.scrollWidth).toBeLessThanOrEqual(root.clientWidth + 1);
      for (const [index, panel] of panels.entries()) {
        const key = index === 0 ? 'metadata:field:field-59' : 'ui:field:list:field-59';
        const target = panel.get(`[data-ui-tree-key="${key}"]`).element;
        const header = panel.get('.record-explorer-panel-header').element;
        const top = header.getBoundingClientRect().top;
        await page.elementLocator(target).click();
        const scrollOwners = [...panel.element.querySelectorAll<HTMLElement>('*')].filter(
          (element) => element.scrollTop > 0 && element.scrollHeight > element.clientHeight,
        );
        expect(scrollOwners.length).toBeGreaterThan(0);
        expect(header.getBoundingClientRect().top).toBe(top);
        expect(target.getBoundingClientRect().bottom).toBeLessThanOrEqual(
          panel.element.getBoundingClientRect().bottom,
        );
      }
      // Reload clears selection. The first click of a double click must not shift the next hit.
      await page.getByRole('button', { name: '刷新：页面结构', exact: true }).click();
      await expect.poll(() => wrapper.find('.ui-tree__operations').exists()).toBe(false);
      const quick = wrapper.get('[data-ui-tree-key="ui:template:list:quick-search"]');
      await page.elementLocator(quick.element).dblClick();
      await expect.element(page.getByRole('textbox', { name: '搜索占位提示', exact: true })).toBeVisible();
      await page.getByRole('textbox', { name: '搜索占位提示', exact: true }).fill('布局验收');
      await page.getByRole('button', { name: '关闭', exact: true }).click();
      await expect.element(page.getByRole('button', { name: '保存草稿', exact: true })).toBeEnabled();
      await expect.poll(() => wrapper.find('[data-testid="page-composer-list-preview"]').exists()).toBe(true);
      await expect
        .poll(() => !(wrapper.get('input[value="detail"]').element as HTMLInputElement).disabled)
        .toBe(true);
      await page.elementLocator(wrapper.get('input[value="detail"]').element.closest('label')!).click();
      await expect
        .poll(() => wrapper.find('[data-page-composition-layout-key="detail:field:field0"]').exists())
        .toBe(true);
      const detailField = wrapper.get('[data-page-composition-layout-key="detail:field:field0"]').element;
      await page.elementLocator(detailField).dblClick();
      await expect.element(page.getByRole('radio', { name: '页面', exact: true })).toBeChecked();
      await expect.element(page.getByRole('textbox', { name: '展示标题', exact: true })).toBeVisible();
      await page.getByRole('button', { name: '关闭', exact: true }).click();
      await expect
        .poll(() => !(wrapper.get('input[value="edit"]').element as HTMLInputElement).disabled)
        .toBe(true);
      await page.elementLocator(wrapper.get('input[value="edit"]').element.closest('label')!).click();
      await expect.element(page.getByRole('radio', { name: '表单', exact: true })).toBeChecked();
      await expect.element(page.getByRole('textbox', { name: '字段59', exact: true })).toBeInTheDocument();
      const content = wrapper.get('.record-detail-panel-region .record-detail-layout-content')
        .element as HTMLElement;
      expect(content.scrollHeight).toBeGreaterThan(content.clientHeight);
      const save = page.getByRole('button', { name: '保存草稿', exact: true });
      const header = wrapper.get('.page-composition-workspace > .management-panel-header').element;
      const top = header.getBoundingClientRect().top;
      await page.getByRole('textbox', { name: '字段59', exact: true }).fill('末尾可编辑');
      expect(content.scrollTop).toBeGreaterThan(0);
      expect(header.getBoundingClientRect().top).toBe(top);
      await expect.element(save).toBeVisible();
      expect(root.getBoundingClientRect().bottom).toBeLessThanOrEqual(814);
      const previewPanel = wrapper.get('.record-detail-panel-region').element;
      const originalWidth = previewPanel.getBoundingClientRect().width;
      await page.getByRole('button', { name: '收起可用字段', exact: true }).click();
      await expect.poll(() => previewPanel.getBoundingClientRect().width).toBeGreaterThan(originalWidth);
      const oneCollapsedWidth = previewPanel.getBoundingClientRect().width;
      await page.getByRole('button', { name: '收起页面结构', exact: true }).click();
      await expect.poll(() => previewPanel.getBoundingClientRect().width).toBeGreaterThan(oneCollapsedWidth);
      await expect.poll(() => root.scrollWidth).toBeLessThanOrEqual(root.clientWidth + 1);
      await page.getByRole('button', { name: '展开页面结构', exact: true }).click();
      await page.getByRole('button', { name: '展开可用字段', exact: true }).click();
      await expect.poll(() => previewPanel.getBoundingClientRect().width).toBeCloseTo(originalWidth, 0);
      expect(wrapper.find('.record-explorer-panel-footer').exists()).toBe(false);
    } finally {
      wrapper.unmount();
    }
  },
);

function layoutHttp(): HttpClient {
  const fields = Array.from({ length: 60 }, (_, index) => ({
    id: `field-${index}`,
    fieldName: `field${index}`,
    title: `字段${index}`,
    fieldOwnership: 'BUSINESS',
    fieldForm: 'PHYSICAL',
  }));
  const uiFields = fields.map((field) => ({
    fieldRef: { fieldName: field.fieldName },
    label: field.title,
    uiType: 'input',
  }));
  const list = (records: unknown[]) => ({ records, pages: 1, totalKnown: true });
  return {
    request: async <T>(request: { path: string; body?: unknown }) => {
      const path = request.path;
      if (path.endsWith('/overview-mode'))
        return {
          overviewMode: 'LIST_CARD',
          platformFieldPolicies: [
            { fieldName: 'id', composable: false, readOnly: true },
            { fieldName: 'tenantId', composable: false, readOnly: true },
            { fieldName: 'authUserId', composable: false, readOnly: true },
            { fieldName: 'createdAt', composable: true, readOnly: true },
            { fieldName: 'createdBy', composable: true, readOnly: true, referenceModuleAlias: 'iam.user' },
          ],
          compositionSkeletons: [
            {
              mode: 'LIST_CARD',
              title: '列表 + 卡片',
              navigationTitle: '记录列表',
              fieldGroupTitle: '列表展示字段',
              columns: true,
              maxIdentityFields: 0,
            },
          ],
        } as T;
      if (path.endsWith('/context')) return { capabilities: [], actions: [] } as T;
      if (path.endsWith('/page-reference-fields'))
        return {
          moduleAlias: 'education.layout',
          path: '',
          fields: fields.map((field) => ({ id: field.id, name: field.fieldName, label: field.title })),
        } as T;
      if (path.endsWith('/metadata-relations/query'))
        return list([{ id: 'main', metadataId: 'main', relationAlias: '主实体', relationRole: 'main' }]) as T;
      if (path.endsWith('/fields/query')) return list(fields) as T;
      if (path.endsWith('/pages/query'))
        return list([{ id: 'page', alias: 'management', title: '布局验收', mainRelationId: 'main' }]) as T;
      if (path.endsWith('/presentation-variants/query'))
        return list([{ id: 'variant', pageId: 'page' }]) as T;
      if (path.endsWith('/revisions/query'))
        return list(
          JSON.stringify(request.body).includes('published')
            ? []
            : [
                {
                  id: 'draft',
                  revisionNo: 1,
                  version: 0,
                  uiTreeJson: JSON.stringify({
                    template: 'management',
                    templateVersion: 1,
                    nodes: ['list', 'form'].map((slot) => ({
                      slot,
                      fields: fields.map((field) => field.fieldName),
                    })),
                  }),
                },
              ],
        ) as T;
      if (path.endsWith('/preview'))
        return {
          uiDescriptor: {
            schemaVersion: '1',
            moduleAlias: 'education.layout',
            page: {
              template: 'LIST_DETAIL_CARD',
              traits: [],
              list: { fields: { viewCode: 'list', viewKind: 'LIST', fields: uiFields } },
              detail: {
                createTitle: '新建',
                emptyDescription: '暂无详情',
                editor: { viewCode: 'editor', viewKind: 'FORM', fields: uiFields },
              },
            },
          },
        } as T;
      throw new Error(`Unexpected request ${path}`);
    },
  };
}

it('refreshes metadata after returning to a cached composer without discarding unsaved configuration', async () => {
  const base = layoutHttp();
  let catalogueChanged = false;
  configureModuleContext({
    http: {
      request: async <T>(request: Parameters<HttpClient['request']>[0]) => {
        const result = await base.request<T>(request);
        if (catalogueChanged && request.path.endsWith('/fields/query')) {
          const records = (result as { records: unknown[] }).records;
          return {
            ...result,
            records: [
              ...records,
              {
                id: 'new-field',
                fieldName: 'newField',
                title: '新增字段',
                fieldOwnership: 'BUSINESS',
                fieldForm: 'PHYSICAL',
              },
            ],
          } as T;
        }
        return result;
      },
    },
  });
  const active = ref(true);
  const wrapper = mount(
    defineComponent({
      setup() {
        providePageLayout('workspace');
        return () =>
          h('div', { style: 'height: 650px' }, [
            h(KeepAlive, null, {
              default: () =>
                active.value
                  ? h(PageCompositionWorkspace, { moduleAlias: 'education.layout' })
                  : h('div', '元数据'),
            }),
          ]);
      },
    }),
    { attachTo: document.body },
  );
  try {
    await expect.element(page.getByRole('button', { name: '发布草稿', exact: true })).toBeEnabled();
    await page
      .elementLocator(wrapper.get('[data-ui-tree-key="ui:template:list:quick-search"]').element)
      .dblClick();
    await page.getByRole('textbox', { name: '搜索占位提示', exact: true }).fill('保留未保存内容');
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    active.value = false;
    await nextTick();
    catalogueChanged = true;
    active.value = true;
    await nextTick();
    await expect
      .poll(() => wrapper.find('[data-ui-tree-key="metadata:field:new-field"]').exists())
      .toBe(true);
    await page
      .elementLocator(wrapper.get('[data-ui-tree-key="ui:template:list:quick-search"]').element)
      .dblClick();
    await expect
      .element(page.getByRole('textbox', { name: '搜索占位提示', exact: true }))
      .toHaveValue('保留未保存内容');
    await expect.element(page.getByRole('button', { name: '保存草稿', exact: true })).toBeEnabled();
  } finally {
    wrapper.unmount();
  }
});
