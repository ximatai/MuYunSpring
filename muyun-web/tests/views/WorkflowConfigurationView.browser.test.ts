import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import { UiThemeProvider } from '@muyun/vue-ui-antdv';
import { ModuleContextProvider, type ModuleContext, type HttpRequestOptions } from '@muyun/web-core';
import { providePageLayout } from '@/platform-components/pageLayoutContext';
import WorkflowConfigurationView from '@/views/WorkflowConfigurationView.vue';
import '@/styles.css';
import '@muyun/vue-ui-antdv/styles.css';

it('aligns workflow settings and keeps creation actions visible when matching settings scroll', async () => {
  await page.viewport(1038, 804);
  const context = {
    moduleAlias: 'platform.workflow.definition',
    http: {
      async request(options: HttpRequestOptions) {
        if (options.path.endsWith('/workflow-definitions/query'))
          return {
            records: [{ id: 'purchase', alias: 'purchase', title: '采购审批', definitionStatus: 'draft' }],
          };
        if (options.path.endsWith('/configuration-catalog'))
          return { tasks: [], queries: [], generations: [], associations: [] };
        if (options.path.endsWith('/versions/query'))
          return { records: [{ id: 'draft', versionNo: 1, version: 1, publishStatus: 'draft' }] };
        if (options.path.endsWith('/design')) return { nodes: [], links: [] };
        return { moduleAlias: 'demo.purchase', records: [], capabilities: [], actions: [] };
      },
    },
  } as unknown as ModuleContext<unknown>;
  const Harness = defineComponent({
    setup() {
      providePageLayout('workspace');
      return () =>
        h('div', { style: 'height:700px' }, [
          h(UiThemeProvider, {}, () =>
            h(ModuleContextProvider, { context }, () =>
              h(WorkflowConfigurationView, { moduleAlias: 'demo.purchase' }),
            ),
          ),
        ]);
    },
  });
  const wrapper = mount(Harness, {
    attachTo: document.body,
    global: {
      stubs: {
        RecordDetailDrawer: true,
        WorkflowDiagram: true,
        WorkflowDesignProperties: true,
      },
    },
  });
  wrapper.element.setAttribute('style', 'height:700px');
  try {
    await flushPromises();
    const workspace = wrapper.get('.static-management-page').element.getBoundingClientRect();
    const explorer = wrapper.get('.record-explorer-panel').element.getBoundingClientRect();
    const detail = wrapper.get('.record-detail-panel-region').element.getBoundingClientRect();
    expect(workspace.left).toBe(wrapper.get('.workflow-configuration').element.getBoundingClientRect().left);
    expect(explorer.width).toBe(280);
    expect(detail.left - explorer.right).toBe(12);
    expect(detail.top).toBe(explorer.top);
    const createTrigger = wrapper.get('.record-panel-create-button').element.getBoundingClientRect();
    expect(createTrigger.width).toBe(28);
    expect(createTrigger.height).toBe(28);
    await page.getByText('采购审批', { exact: true }).click();
    await flushPromises();
    const actions = wrapper.get('.management-panel-header .configuration-action-bar').element;
    expect(actions.getBoundingClientRect().right).toBeLessThanOrEqual(detail.right);
    expect(actions.closest('.record-detail-panel-region')).not.toBeNull();
    await page.getByRole('button', { name: '新建流程', exact: true }).click();
    expect(wrapper.get('.management-explorer-column').attributes('inert')).toBeDefined();
    const create = page.getByRole('button', { name: '创建流程草稿', exact: true });
    await expect.element(create).toBeVisible();
    await page.getByText('适用范围与匹配规则（可选）', { exact: true }).click();
    await flushPromises();
    const checkbox = wrapper.get('input[type="checkbox"]').element;
    const label = checkbox.closest('label')!;
    const mark = checkbox.parentElement!.getBoundingClientRect();
    const text = label.lastElementChild!.getBoundingClientRect();
    expect(Math.abs(mark.top + mark.height / 2 - text.top - text.height / 2)).toBeLessThan(4);
    expect(text.left).toBeGreaterThanOrEqual(mark.right);
    expect(getComputedStyle(wrapper.get('.workflow-field').element).fontSize).toBe('13px');
    expect(getComputedStyle(wrapper.get('.workflow-field input').element).fontSize).toBe('13px');
    expect(getComputedStyle(wrapper.get('.settings-help').element).fontSize).toBe('12px');
    expect(getComputedStyle(wrapper.get('summary').element).fontSize).toBe('13px');
    expect(getComputedStyle(wrapper.get('.record-detail-layout h2').element).fontSize).toBe('16px');
    const priority = wrapper.get('input[type="number"]').element.getBoundingClientRect();
    expect(priority.height).toBeLessThan(40);
    const operation = wrapper.get('.record-detail-layout-operation').element.getBoundingClientRect();
    expect(operation.bottom).toBeLessThanOrEqual(700);
    expect(operation.height).toBeGreaterThan(30);
    const content = wrapper.get('.record-detail-layout-content').element;
    content.scrollTop = content.scrollHeight;
    await expect.element(create).toBeVisible();
    expect(wrapper.get('.record-detail-layout-operation').element.getBoundingClientRect().top).toBe(
      operation.top,
    );
  } finally {
    wrapper.unmount();
  }
});
