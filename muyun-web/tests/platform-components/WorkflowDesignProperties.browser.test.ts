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
  wrapper.element.setAttribute('style', 'width:732px');
  const checkbox = page.getByRole('checkbox', { name: '允许驳回', exact: true });
  await expect.element(checkbox).toBeVisible();
  const input = wrapper.get('input[type="checkbox"]').element;
  const label = input.closest('label')!;
  const mark = input.parentElement!.getBoundingClientRect();
  const text = label.lastElementChild!.getBoundingClientRect();
  expect(Math.abs(mark.top + mark.height / 2 - text.top - text.height / 2)).toBeLessThan(4);
  expect(text.left).toBeGreaterThanOrEqual(mark.right);
  await wrapper.setProps({ nodeKey: 'arrival' });
  const task = wrapper.get('.task-editor').element;
  expect(task.getBoundingClientRect().width).toBeCloseTo(wrapper.element.getBoundingClientRect().width, 0);
  for (const width of [492, 328]) {
    wrapper.element.setAttribute('style', `width:${width}px`);
    await expect.poll(() => task.getBoundingClientRect().width).toBe(width);
    expect(wrapper.element.scrollWidth).toBeLessThanOrEqual(width);
    const manual = task.querySelector('input[type="checkbox"]')!.closest('label')!;
    expect(getComputedStyle(manual).display).not.toBe('grid');
  }
});
