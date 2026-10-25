import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import RecordQueryListSurface from '@/platform-components/RecordQueryListSurface.vue';
import '@/styles.css';
import 'ant-design-vue/dist/reset.css';

it('keeps English job headers readable beside an open detail pane at 1440px', async () => {
  await page.viewport(1440, 1000);
  // The backend declaration contract locks these widths on JobPlanWebController.
  const columns = [
    { key: 'tenantId', title: 'Owning tenant ID', width: '180px' },
    { key: 'jobType', title: 'Job type', width: '260px' },
    { key: 'scope', title: 'Scope', width: '140px' },
    { key: 'enabled', title: 'Enabled', width: '120px' },
    { key: 'available', title: 'Type available', width: '140px' },
    { key: 'nextFireAt', title: 'Next fire time', width: '200px' },
    { key: 'lastStatus', title: 'Last run status', width: '170px' },
    { key: 'consecutiveFailures', title: 'Consecutive failures', width: '180px' },
  ];
  const wrapper = mount(RecordQueryListSurface, {
    attachTo: document.body,
    attrs: { style: 'width: 720px; height: 600px' },
    props: { columns, rows: [{ id: 'scan', jobType: 'Workflow overtime scan' }] },
  });
  try {
    await expect.poll(() => wrapper.findAll('th').length).toBe(columns.length);
    const header = wrapper.findAll('th').find((cell) => cell.text() === 'Owning tenant ID')!;
    await expect.poll(() => header.element.getBoundingClientRect().width).toBeGreaterThanOrEqual(179);
    expect(header.element.getBoundingClientRect().height).toBeLessThan(80);
    const scroll = wrapper.get('.ant-table-body').element;
    expect(scroll.scrollWidth).toBeGreaterThan(scroll.clientWidth);
    expect(wrapper.element.scrollWidth).toBeLessThanOrEqual(wrapper.element.clientWidth + 1);
  } finally {
    wrapper.unmount();
  }
});
