import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it } from 'vitest';
import type { WorkflowDefinition } from '@muyun/web-contracts';
import { page } from 'vitest/browser';
import { UiSidePanelHost, UiThemeProvider } from '@muyun/vue-ui-antdv';
import { ModuleContextProvider, type ModuleContext, type HttpRequestOptions } from '@muyun/web-core';
import { RecordDetailDrawer } from '@muyun/platform-components';
import DrawerTitleActions from '@/platform-components/DrawerTitleActions.vue';
import ModuleWorkflowDrawer from '@/platform-admin-runtime/workflow/ModuleWorkflowDrawer.vue';
import { useModulePageDetailExtensionRuntime } from '@/dynamic-page-runtime/composables/useModulePageDetailExtensionRuntime';
import '@/styles.css';
import '@muyun/vue-ui-antdv/styles.css';

function setup(records: WorkflowDefinition[] = []) {
  const request = async (call: HttpRequestOptions) => {
    if (call.path.endsWith('/workflow-definitions/query')) return { records };
    if (call.path.endsWith('/formula-functions')) return [];
    return { moduleAlias: 'demo.purchase', capabilities: [], actions: [] };
  };
  const context = {
    moduleAlias: 'platform.workflow.definition',
    can: () => true,
    http: { request },
  } as unknown as ModuleContext<Record<string, unknown>>;
  const Harness = defineComponent({
    setup() {
      const runtime = useModulePageDetailExtensionRuntime({
        module: context,
        scope: () => undefined,
        refreshList: () => {},
        reload: () => {},
        closeDetail: () => {},
      });
      runtime.openDrawer(
        { title: '审批流程', component: ModuleWorkflowDrawer },
        { alias: 'demo.purchase', title: '采购' },
      );
      return () =>
        h(UiThemeProvider, {}, () =>
          h(UiSidePanelHost, { style: 'height: 100vh' }, () =>
            h(ModuleContextProvider, { context }, () =>
              h(
                RecordDetailDrawer,
                { open: runtime.drawerOpen.value, title: '审批流程', renderMode: 'inline', width: 'wide' },
                {
                  default: () => h(ModuleWorkflowDrawer, { context: runtime.drawer.value!.context }),
                  ...(runtime.drawer.value!.operation
                    ? {
                        operation: () =>
                          h(DrawerTitleActions, { actions: runtime.drawer.value!.operation!.actions }),
                      }
                    : {}),
                },
              ),
            ),
          ),
        );
    },
  });
  return mount(Harness, { attachTo: document.body });
}

it('uses the standard drawer and keeps draft actions fixed at the bottom while formula settings scroll', async () => {
  await page.viewport(1009, 804);
  const wrapper = setup([
    { id: 'existing', version: 1, title: '已有流程', definitionStatus: 'draft', approvalEnabled: true },
  ]);
  try {
    await flushPromises();
    await page.getByRole('button', { name: '新建流程', exact: true }).click();
    await page.getByRole('textbox', { name: '流程名称', exact: true }).fill('采购审批');
    const editor = wrapper
      .findAll('.record-detail-layout')
      .find((layout) => layout.get('h2').text() === '新建流程')!;
    expect(wrapper.findAllComponents({ name: 'RecordDetailDrawer' })).toHaveLength(2);
    expect(wrapper.findComponent({ name: 'UiDataTable' }).props('rows')).toHaveLength(1);
    const outer = wrapper.get('.record-detail-layout').element.getBoundingClientRect();
    const host = wrapper.get('.ui-side-panel-host').element.getBoundingClientRect();
    const inner = editor.element.getBoundingClientRect();
    const drawer = editor.element.closest('.ant-drawer')!;
    expect(wrapper.get('.ui-side-panel-host').element.contains(drawer)).toBe(true);
    expect(wrapper.get('.record-detail-layout').element.contains(drawer)).toBe(false);
    expect(inner.width).toBe(520);
    expect(inner.left).toBeGreaterThanOrEqual(host.left);
    expect(inner.width).toBeLessThan(outer.width);
    expect(inner.right).toBeLessThanOrEqual(host.right);
    expect(wrapper.findAll('input').some((item) => item.attributes('aria-label') === '流程编码')).toBe(false);
    expect(wrapper.text()).not.toContain('流程编码');
    await page.getByText('适用范围与匹配规则（可选）', { exact: true }).click();
    await page.getByRole('button', { name: '公式辅助', exact: true }).click();
    await flushPromises();
    await page.getByText('驱动业务审批状态', { exact: true }).click();
    await page.getByRole('textbox', { name: '模块动作编码', exact: true }).fill('syncData');
    await expect.element(page.getByRole('textbox', { name: '模块动作编码', exact: true })).toBeVisible();
    const create = page.getByRole('button', { name: '创建流程草稿', exact: true });
    await expect.element(create).toBeVisible();
    const button = wrapper
      .findAll('button')
      .find((item) => item.text().replace(/\s/g, '') === '创建流程草稿')!;
    expect(button.element.closest('.record-detail-layout-operation')).not.toBeNull();
    expect(button.element.closest('.record-detail-layout-header')).toBeNull();
    const operation = editor.get('.record-detail-layout-operation').element.getBoundingClientRect();
    const bounds = button.element.getBoundingClientRect();
    expect(operation.right - bounds.right).toBeLessThan(30);
    expect(operation.bottom).toBeLessThanOrEqual(804);
    const content = editor.get('.record-detail-layout-content').element;
    content.scrollTop = content.scrollHeight;
    await expect.element(create).toBeVisible();
    expect(editor.get('.record-detail-layout-operation').element.getBoundingClientRect().top).toBe(
      operation.top,
    );
    const tools = wrapper.get('.formula-expression-editor__tools').element.getBoundingClientRect();
    const panel = editor.get('.record-detail-layout-content').element.getBoundingClientRect();
    expect(tools.right).toBeLessThanOrEqual(panel.right);
    expect(wrapper.find('input[type="number"]').exists()).toBe(false);
  } finally {
    wrapper.unmount();
  }
});

it.each([1009, 1280])(
  'keeps standard table headers and row operations usable in a %ipx drawer',
  async (width) => {
    await page.viewport(width, 804);
    const wrapper = setup([
      {
        id: 'published',
        version: 1,
        title: '已发布的采购审批流程',
        matchExpression: '{amount} > 100 && '.repeat(20) + 'true',
        definitionStatus: 'published',
        currentVersionNo: 1,
        approvalEnabled: true,
      },
      { id: 'draft', version: 1, title: '待配置流程', definitionStatus: 'draft', approvalEnabled: true },
    ]);
    try {
      await flushPromises();
      for (const name of ['流程名称', '流程状态', '发布版本', '匹配条件', '启用状态', '操作'])
        await expect.element(page.getByText(name, { exact: true })).toBeVisible();
      expect(wrapper.findComponent({ name: 'RecordListExplorer' }).exists()).toBe(false);
      const switches = wrapper.findAllComponents({ name: 'UiSwitch' });
      expect(switches.map((control) => control.props('disabled'))).toEqual([false, true]);
      const content = wrapper.get('.record-detail-layout-content').element.getBoundingClientRect();
      const operations = wrapper
        .findAll('button')
        .filter((button) => ['编排', '设置', '归档'].includes(button.text().replace(/\s/g, '')));
      expect(operations).toHaveLength(6);
      for (const operation of operations) {
        const bounds = operation.element.getBoundingClientRect();
        expect(bounds.left).toBeGreaterThanOrEqual(content.left);
        expect(bounds.right).toBeLessThanOrEqual(content.right);
      }
      expect(
        wrapper.get('.record-query-list-surface').element.getBoundingClientRect().width,
      ).toBeLessThanOrEqual(content.width);
    } finally {
      wrapper.unmount();
    }
  },
);
