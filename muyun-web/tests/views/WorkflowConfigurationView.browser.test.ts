import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import { UiThemeProvider, UiSidePanelHost } from '@muyun/vue-ui-antdv';
import { ModuleContextProvider, type ModuleContext, type HttpRequestOptions } from '@muyun/web-core';
import { providePageLayout } from '@/platform-components/pageLayoutContext';
import WorkflowConfigurationView from '@/views/WorkflowConfigurationView.vue';
import '@/styles.css';
import '@muyun/vue-ui-antdv/styles.css';

it('uses the whole workspace for one definition and keeps design actions inside its header', async () => {
  await page.viewport(1009, 804);
  const request = async (call: HttpRequestOptions) => {
    if (call.path.endsWith('/view/a')) return { id: 'a', alias: 'purchase', title: '采购审批', version: 1 };
    if (call.path.endsWith('/versions/query'))
      return { records: [{ id: 'draft', version: 1, versionNo: 1, publishStatus: 'draft' }] };
    if (call.path.endsWith('/configuration-catalog'))
      return { tasks: [], queries: [], generations: [], associations: [] };
    if (call.path.endsWith('/design'))
      return {
        nodes: [
          { nodeKey: 'start', nodeType: 'start', title: '提交' },
          { nodeKey: 'approval', nodeType: 'approval', title: '审批' },
          { nodeKey: 'end', nodeType: 'end', title: '结束' },
        ],
        links: [
          { routeKey: 'in', sourceNodeKey: 'start', targetNodeKey: 'approval' },
          { routeKey: 'out', sourceNodeKey: 'approval', targetNodeKey: 'end' },
        ],
      };
    return { moduleAlias: 'demo.purchase', capabilities: [], actions: [] };
  };
  const context = {
    moduleAlias: 'platform.workflow.definition',
    http: { request },
  } as unknown as ModuleContext<unknown>;
  const Harness = defineComponent({
    setup() {
      providePageLayout('workspace');
      return () =>
        h(UiThemeProvider, {}, () =>
          h(UiSidePanelHost, {}, () =>
            h(ModuleContextProvider, { context }, () =>
              h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase', definitionId: 'a' }),
            ),
          ),
        );
    },
  });
  const wrapper = mount(Harness, {
    attachTo: document.body,
    global: { stubs: { RecordDetailDrawer: true } },
  });
  wrapper.element.setAttribute('style', 'display:block;height:700px');
  try {
    await flushPromises();
    expect(wrapper.find('.record-explorer-panel').exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'RecordAdaptiveDetailPanel' }).props('width')).toBe('compact');
    const detail = wrapper.get('.record-detail-panel-region').element.getBoundingClientRect();
    const root = wrapper.get('.workflow-configuration').element.getBoundingClientRect();
    expect(detail.width).toBeCloseTo(root.width, 0);
    const actions = wrapper.get('.configuration-action-bar').element.getBoundingClientRect();
    expect(actions.right).toBeLessThanOrEqual(detail.right);
    await expect.element(page.getByRole('button', { name: '保存草稿', exact: true })).toBeVisible();
    expect(wrapper.findComponent({ name: 'WorkflowDiagram' }).props('editable')).toBe(true);
    for (const width of [1009, 1280, 640]) {
      await page.viewport(width, 804);
      await expect
        .poll(() => wrapper.get('.diagram-viewport').element.getBoundingClientRect().height)
        .toBeGreaterThan(590);
      const viewport = wrapper.get('.diagram-viewport').element.getBoundingClientRect();
      const rootBounds = wrapper.element.getBoundingClientRect();
      expect(viewport.height / rootBounds.height).toBeGreaterThan(0.84);
      expect(wrapper.element.scrollWidth).toBeLessThanOrEqual(rootBounds.width);
      const controls = ['.diagram-edit-tools', '.diagram-navigation', '.diagram-tools'];
      for (const selector of controls) {
        const bounds = wrapper.get(selector).element.getBoundingClientRect();
        expect(bounds.top).toBeGreaterThanOrEqual(viewport.top);
        expect(bounds.bottom).toBeLessThanOrEqual(viewport.bottom);
        expect(bounds.left).toBeGreaterThanOrEqual(viewport.left);
        expect(bounds.right).toBeLessThanOrEqual(viewport.right);
      }
      const tools = wrapper.get('.diagram-tools').element.getBoundingClientRect();
      expect(tools.left - viewport.left).toBeCloseTo(12, 0);
      expect(viewport.bottom - tools.bottom).toBeCloseTo(12, 0);
      const iconButtons = wrapper
        .findAll('.diagram-tools button')
        .filter((button) => button.find('svg').exists());
      expect(iconButtons).toHaveLength(6);
      for (const button of iconButtons) {
        expect(button.attributes('title')).toBe(button.attributes('aria-label'));
        expect(button.attributes('aria-label')).toBeTruthy();
      }
      const add = wrapper.get('.diagram-edit-tools').element.getBoundingClientRect();
      const navigation = wrapper.get('.diagram-navigation').element.getBoundingClientRect();
      expect(add.right <= navigation.left || add.bottom <= navigation.top).toBe(true);
    }
    await page.viewport(1009, 804);
    await page.getByRole('button', { name: '放大', exact: true }).click();
    expect(wrapper.find('[role="status"]').exists()).toBe(false);
    await page.getByRole('button', { name: '提交 开始', exact: true }).click();
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    await page.getByRole('button', { name: '添加节点 / 连线', exact: false }).click();
    await page.getByRole('menuitem', { name: '插入审批节点', exact: true }).click();
    await expect.poll(() => wrapper.findComponent({ name: 'WorkflowDiagram' }).props('nodes').length).toBe(4);
    expect(wrapper.findComponent({ name: 'RecordAdaptiveDetailPanel' }).props('open')).toBe(true);
    expect(wrapper.get('[role="status"]').text()).toBe('未保存');
    const properties = wrapper.findComponent({ name: 'WorkflowDesignProperties' });
    const editorInstance = properties.vm.$;
    const input = properties.get('input').element as HTMLInputElement;
    await page.getByRole('textbox', { name: '节点名称', exact: true }).fill('尚未保存的节点配置');
    await expect.element(page.getByRole('button', { name: '115%', exact: true })).toBeVisible();
    const graph = wrapper.get('.diagram-canvas').element;
    const before = wrapper.get('[data-workflow-node="start"]').element.getBoundingClientRect();
    for (const width of [1280, 1009, 1440, 1009]) {
      await page.viewport(width, 804);
      await expect
        .poll(() => wrapper.findComponent({ name: 'RecordAdaptiveDetailPanel' }).props('docked'))
        .toBe(width >= 1280);
      await expect
        .poll(
          () =>
            input.closest(
              width >= 1280 ? '.record-adaptive-detail__dock' : '.record-adaptive-detail__drawer',
            ) !== null,
        )
        .toBe(true);
      expect(
        wrapper.findComponent({ name: 'WorkflowDesignProperties' }).vm.$ === editorInstance,
        `editor instance at ${width}`,
      ).toBe(true);
      expect(
        wrapper.findComponent({ name: 'WorkflowDesignProperties' }).get('input').element === input,
        `input identity at ${width}`,
      ).toBe(true);
      expect(input.value).toBe('尚未保存的节点配置');
      expect(wrapper.get('.diagram-canvas').element === graph, `graph instance at ${width}`).toBe(true);
      await expect.element(page.getByRole('button', { name: '115%', exact: true })).toBeVisible();
      const after = wrapper.get('[data-workflow-node="start"]').element.getBoundingClientRect();
      expect(after.left).toBeCloseTo(before.left, 0);
      expect(after.top).toBeCloseTo(before.top, 0);
      if (width >= 1280) {
        const dock = wrapper.get('.record-adaptive-detail__dock').element.getBoundingClientRect();
        expect(dock.width).toBe(420);
        expect(wrapper.get('.diagram-viewport').element.getBoundingClientRect().right).toBeLessThanOrEqual(
          dock.left,
        );
      }
    }
  } finally {
    wrapper.unmount();
  }
});
