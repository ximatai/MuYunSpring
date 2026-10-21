import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
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
    await page.getByRole('button', { name: '添加节点 / 分支', exact: false }).click();
    await page.getByRole('menuitem', { name: '插入节点', exact: true }).click();
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
      const footer = document.querySelector('.configuration-property-operations')!;
      const footerButtons = footer.querySelectorAll('button');
      expect(footerButtons[0].textContent).toContain('删除节点');
      const left = footerButtons[0].getBoundingClientRect();
      const right = footerButtons[1].getBoundingClientRect();
      const footerBounds = footer.getBoundingClientRect();
      expect(left.left).toBeCloseTo(footerBounds.left, 0);
      expect(right.right).toBeCloseTo(footerBounds.right, 0);
      expect(left.top).toBeCloseTo(right.top, 0);
      expect(properties.findAll('button').some((button) => button.text().includes('删除并连接'))).toBe(false);
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
    await page.getByRole('button', { name: '删除节点', exact: true }).click();
    await expect.poll(() => wrapper.findComponent({ name: 'WorkflowDiagram' }).props('nodes').length).toBe(3);
    wrapper.findComponent({ name: 'WorkflowDiagram' }).vm.$emit('select', 'start');
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    await page.getByRole('button', { name: '添加节点 / 分支', exact: false }).click();
    expect(await page.getByRole('menuitem', { name: '添加连线', exact: true }).all()).toHaveLength(0);
    await page.getByRole('menuitem', { name: '添加分支', exact: true }).click();
    const diagram = wrapper.findComponent({ name: 'WorkflowDiagram' });
    await expect.poll(() => diagram.props('nodes').length).toBe(7);
    const branch = diagram.props('nodes').find((item: { nodeType: string }) => item.nodeType === 'branch');
    await page.getByRole('button', { name: '增加分支路径', exact: true }).click();
    await expect.poll(() => diagram.props('nodes').length).toBe(8);
    const exits = diagram
      .props('routes')
      .filter((item: { sourceNodeKey: string }) => item.sourceNodeKey === branch.nodeKey);
    expect(exits).toHaveLength(3);
    for (const exit of exits) {
      expect(
        diagram
          .props('routes')
          .some(
            (item: { sourceNodeKey: string; targetNodeKey: string }) =>
              item.sourceNodeKey === exit.targetNodeKey && item.targetNodeKey === branch.convergeNodeKey,
          ),
      ).toBe(true);
    }
    await page.getByRole('button', { name: '配置出口：路径1', exact: true }).click();
    const routeProperties = wrapper.findComponent({ name: 'WorkflowDesignProperties' });
    expect(
      routeProperties.findAllComponents({ name: 'UiSelect' }).every((select) => select.props('disabled')),
    ).toBe(true);
    expect(document.querySelector('.configuration-property-operations')!.textContent).not.toContain('删除');
    const canvas = wrapper.get('.diagram-viewport').element as HTMLElement;
    canvas.focus();
    await userEvent.keyboard('{Control>}a{/Control}');
    await expect.poll(() => diagram.props('selectedNodeKeys').length).toBe(8);
    let copiedText = '';
    const write = vi.spyOn(navigator.clipboard, 'writeText').mockImplementation(async (text) => {
      copiedText = text;
    });
    const read = vi.spyOn(navigator.clipboard, 'readText').mockImplementation(async () => copiedText);
    try {
      await userEvent.keyboard('{Control>}c{/Control}');
      expect(write).toHaveBeenCalledOnce();
      expect(JSON.parse(copiedText).kind).toBe('muyun.workflow.fragment');
      await userEvent.keyboard('{Control>}v{/Control}');
      expect(read).toHaveBeenCalledOnce();
    } finally {
      write.mockRestore();
      read.mockRestore();
    }
    await expect.poll(() => diagram.props('nodes').length).toBe(14);
    expect(new Set(diagram.props('nodes').map((node: { nodeKey: string }) => node.nodeKey)).size).toBe(14);
    canvas.focus();
    await userEvent.keyboard('{Control>}z{/Control}');
    await expect.poll(() => diagram.props('nodes').length).toBe(8);
    await userEvent.keyboard('{Control>}y{/Control}');
    await expect.poll(() => diagram.props('nodes').length).toBe(14);
    await userEvent.keyboard('{Meta>}z{/Meta}');
    await expect.poll(() => diagram.props('nodes').length).toBe(8);
    await userEvent.keyboard('{Control>}{Shift>}z{/Shift}{/Control}');
    await expect.poll(() => diagram.props('nodes').length).toBe(14);
    await expect.element(page.getByRole('button', { name: '115%', exact: true })).toBeVisible();
    diagram.vm.$emit('select', 'approval');
    await flushPromises();
    const name = page.getByRole('textbox', { name: '节点名称', exact: true });
    await name.fill('只编辑字段');
    const selection = diagram.props('selectedNodeKeys');
    await userEvent.keyboard('{Control>}a{/Control}');
    expect(diagram.props('selectedNodeKeys')).toEqual(selection);
    expect(diagram.props('nodes')).toHaveLength(14);
    await userEvent.keyboard('{Backspace}');
    expect(diagram.props('nodes')).toHaveLength(14);
    canvas.focus();
    await userEvent.keyboard('{Delete}');
    await expect.poll(() => diagram.props('nodes').length).toBe(13);
    diagram.vm.$emit('select', branch.nodeKey);
    await flushPromises();
    canvas.focus();
    await userEvent.keyboard('{Delete}');
    await expect.element(page.getByText('删除整个分支？', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: /取\s*消/ }).click();
    expect(diagram.props('nodes')).toHaveLength(13);
    canvas.focus();
    await userEvent.keyboard('{Backspace}');
    await page.getByRole('button', { name: '删除分支', exact: true }).click();
    await expect.poll(() => diagram.props('nodes').length).toBe(8);
    canvas.focus();
    await userEvent.keyboard('{Control>}z{/Control}');
    await expect.poll(() => diagram.props('nodes').length).toBe(13);
    await userEvent.keyboard('{Control>}z{/Control}');
    await expect.poll(() => diagram.props('nodes').length).toBe(14);
    diagram.vm.$emit('select', 'start');
    await flushPromises();
    canvas.focus();
    await userEvent.keyboard('{Delete}');
    expect(diagram.props('nodes')).toHaveLength(14);
    diagram.vm.$emit('select', 'approval');
    await flushPromises();
    canvas.focus();
    await userEvent.keyboard('{Backspace}');
    await expect.poll(() => diagram.props('nodes').length).toBe(13);
  } finally {
    wrapper.unmount();
  }
});
