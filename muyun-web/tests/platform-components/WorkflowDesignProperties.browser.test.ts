import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import WorkflowDesignProperties from '@/platform-components/WorkflowDesignProperties.vue';
import type { WorkflowDesign } from '@muyun/web-contracts';
import '@/styles.css';
import '@muyun/vue-ui-antdv/styles.css';

it('keeps checkbox labels aligned and task configuration full-width in desktop and narrow panels', async () => {
  await page.viewport(1920, 1080);
  const design: WorkflowDesign = {
    nodes: [
      {
        nodeKey: 'approval',
        nodeType: 'approval',
        title: '采购审批',
        approvalMode: 'all',
        allowReject: true,
        participantPolicyText: '{"rules":[{"type":"INITIATOR_SELF"}]}',
      },
      {
        nodeKey: 'arrival',
        nodeType: 'task',
        title: '到货验收',
        nodeConfigText: JSON.stringify({
          task: { definition: { manualConfirm: true }, checks: [], guides: [] },
        }),
      },
    ],
    links: [],
  };
  const wrapper = mount(WorkflowDesignProperties, {
    attachTo: document.body,
    props: {
      design,
      nodeKey: 'approval',
      routeKey: '',
      editable: true,
      http: {
        async request<T>() {
          return { moduleAlias: 'iam.user', capabilities: [], actions: [] } as T;
        },
      },
      moduleAlias: 'demo.purchase',
      fields: [],
      actions: [],
      catalog: { tasks: [], queries: [], generations: [], associations: [] },
    },
  });
  wrapper.element.setAttribute('style', 'width:384px');
  const checkbox = page.getByRole('checkbox', { name: '允许驳回', exact: true });
  await expect.element(checkbox).toBeVisible();
  const input = wrapper.get('input[type="checkbox"]').element;
  const label = input.closest('label')!;
  const mark = input.parentElement!.getBoundingClientRect();
  const text = label.lastElementChild!.getBoundingClientRect();
  expect(Math.abs(mark.top + mark.height / 2 - text.top - text.height / 2)).toBeLessThan(4);
  expect(text.left).toBeGreaterThanOrEqual(mark.right);
  const fields = wrapper.findAll('.property-form > .workflow-field');
  const first = fields[0].element.getBoundingClientRect();
  const second = fields[1].element.getBoundingClientRect();
  expect(second.top).toBeGreaterThanOrEqual(first.bottom);
  expect(second.left).toBeCloseTo(first.left, 0);
  await wrapper.setProps({ nodeKey: 'arrival' });
  const task = wrapper.get('.task-editor').element;
  expect(task.getBoundingClientRect().width).toBeCloseTo(wrapper.element.getBoundingClientRect().width, 0);
  for (const width of [384, 328]) {
    wrapper.element.setAttribute('style', `width:${width}px`);
    await expect.poll(() => task.getBoundingClientRect().width).toBe(width);
    expect(wrapper.element.scrollWidth).toBeLessThanOrEqual(width);
    const manual = task.querySelector('input[type="checkbox"]')!.closest('label')!;
    expect(getComputedStyle(manual).display).not.toBe('grid');
  }
});

it('keeps every node type and route in one column at compact drawer content widths', async () => {
  await page.viewport(1063, 804);
  const design: WorkflowDesign = {
    nodes: [
      { nodeKey: 'start', nodeType: 'start', title: '提交' },
      {
        nodeKey: 'approval',
        nodeType: 'approval',
        title: '审批',
        approvalMode: 'ratio',
        participantPolicyText: JSON.stringify({
          rules: [{ type: 'RELATIVE', relation: 'SUPERVISOR', depth: 1 }],
        }),
      },
      {
        nodeKey: 'task',
        nodeType: 'task',
        title: '业务任务',
        nodeConfigText: JSON.stringify({
          task: { definition: { manualConfirm: true }, checks: [], guides: [] },
        }),
      },
      {
        nodeKey: 'branch',
        nodeType: 'branch',
        title: '分支',
        routeMode: 'manual',
        convergeNodeKey: 'converge',
      },
      { nodeKey: 'converge', nodeType: 'converge', title: '汇聚', convergeMode: 'ratio' },
      { nodeKey: 'milestone', nodeType: 'milestone', title: '里程碑' },
      { nodeKey: 'end', nodeType: 'end', title: '结束' },
    ],
    links: [{ routeKey: 'route', sourceNodeKey: 'branch', targetNodeKey: 'approval', title: '条件路径' }],
  };
  const wrapper = mount(WorkflowDesignProperties, {
    attachTo: document.body,
    props: {
      design,
      nodeKey: 'start',
      routeKey: '',
      editable: true,
      http: {
        async request<T>() {
          return { moduleAlias: 'iam.user', capabilities: [], actions: [] } as T;
        },
      },
      moduleAlias: 'demo.purchase',
      fields: [],
      actions: [],
      catalog: { tasks: [], queries: [], generations: [], associations: [] },
    },
  });
  try {
    for (const width of [384, 288]) {
      for (const key of [...design.nodes.map((node) => node.nodeKey), 'route']) {
        await wrapper.setProps({ nodeKey: key === 'route' ? '' : key, routeKey: key === 'route' ? key : '' });
        wrapper.element.setAttribute('style', `width:${width}px`);
        await expect.poll(() => Math.round(wrapper.element.getBoundingClientRect().width)).toBe(width);
        expect(wrapper.element.scrollWidth, key).toBeLessThanOrEqual(width);
        const children = wrapper
          .findAll('.property-form > *')
          .map((child) => child.element.getBoundingClientRect())
          .filter((rect) => rect.height > 0);
        for (let i = 1; i < children.length; i++) {
          expect(children[i].top, key).toBeGreaterThanOrEqual(children[i - 1].bottom);
        }
        for (const grid of wrapper.findAll('.participant-rule, .formula-expression-editor__tools')) {
          expect(getComputedStyle(grid.element).gridTemplateColumns.split(' '), key).toHaveLength(1);
        }
      }
    }
  } finally {
    wrapper.unmount();
  }
});

it('shows flat type cards at compact widths and switches FYI with native radio controls', async () => {
  const design: WorkflowDesign = {
    nodes: [{ nodeKey: 'review', nodeType: 'approval', title: '审批', approvalMode: 'all' }],
    links: [],
  };
  const wrapper = mount(WorkflowDesignProperties, {
    attachTo: document.body,
    props: {
      design,
      nodeKey: 'review',
      routeKey: '',
      editable: true,
      http: {
        async request<T>() {
          return {} as T;
        },
      },
      moduleAlias: 'demo.purchase',
      fields: [],
      actions: [],
      catalog: { tasks: [], queries: [], generations: [], associations: [] },
    },
    global: { stubs: { WorkflowParticipantEditor: true } },
  });
  for (const width of [384, 288]) {
    wrapper.element.setAttribute('style', `width:${width}px`);
    await expect.element(page.getByRole('radiogroup', { name: '节点类型' })).toBeVisible();
    const options = wrapper.findAll('.ant-radio-button-wrapper');
    expect(options).toHaveLength(4);
    expect(options.map((option) => getComputedStyle(option.element).borderColor)).toEqual([
      'rgb(0, 82, 217)',
      'rgb(0, 168, 112)',
      'rgb(237, 123, 47)',
      'rgb(245, 190, 44)',
    ]);
    expect(options[1]!.element.getBoundingClientRect().left).toBeGreaterThan(
      options[0]!.element.getBoundingClientRect().left,
    );
    expect(options[2]!.element.getBoundingClientRect().top).toBeGreaterThan(
      options[0]!.element.getBoundingClientRect().top,
    );
    expect(wrapper.element.scrollWidth).toBeLessThanOrEqual(width);
  }
  await page.getByText('知会', { exact: true }).click();
  const patch = wrapper.emitted('update-node')![0]![0];
  expect(patch).toMatchObject({ nodeType: 'approval', approvalMode: 'notice' });
  await wrapper.setProps({ design: { nodes: [{ ...design.nodes[0]!, ...(patch as object) }], links: [] } });
  await expect.element(page.getByRole('radio', { name: /^知会/ })).toBeChecked();
  const selected = wrapper.get('.ant-radio-button-wrapper-checked').element;
  expect(getComputedStyle(selected).borderColor).toBe('rgb(0, 168, 112)');
  expect(getComputedStyle(selected).boxShadow).not.toBe('none');
  expect(getComputedStyle(selected).backgroundColor).not.toBe('rgb(255, 255, 255)');
  expect(wrapper.text()).not.toContain('审批规则');
  await wrapper.setProps({ editable: false });
  await expect.element(page.getByRole('radio', { name: /^审批/ })).toBeDisabled();
  wrapper.unmount();
});
