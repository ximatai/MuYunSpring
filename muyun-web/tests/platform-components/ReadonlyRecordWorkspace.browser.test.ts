import { mount, flushPromises } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { h } from 'vue';
import ReadonlyRecordWorkspace from '@/platform-components/ReadonlyRecordWorkspace.vue';
import { readWorkspaceFixture, readQuerySchema } from './readonlyRecordWorkspaceFixture';
import '@/styles.css';
import '@muyun/vue-ui-antdv/styles.css';

it('keeps one mounted detail tree and its local state across wide/narrow changes', async () => {
  await page.viewport(1600, 900);
  const wrapper = mount(ReadonlyRecordWorkspace, {
    attachTo: document.body,
    props: { view: readWorkspaceFixture() },
    attrs: { style: 'height:700px;width:100%' },
    slots: { detail: () => h('input', { 'aria-label': 'Detail local state' }) },
  });
  try {
    await expect.element(page.getByText('reception A', { exact: true })).toBeVisible();
    await page.getByText('reception A', { exact: true }).click();
    await flushPromises();
    const input = document.querySelector<HTMLInputElement>('input[aria-label="Detail local state"]')!;
    input.value = 'keep local state';
    expect(input.closest('.record-adaptive-detail__dock')).not.toBeNull();
    expect(wrapper.element.getBoundingClientRect().width).toBeLessThanOrEqual(1600);
    await page.viewport(900, 900);
    await expect.poll(() => input.closest('.record-adaptive-detail__drawer') !== null).toBe(true);
    expect(input.value).toBe('keep local state');
    await expect.element(page.getByRole('textbox', { name: 'Detail local state' })).toBeVisible();
    await page.viewport(1600, 900);
    await expect.poll(() => input.closest('.record-adaptive-detail__dock') !== null).toBe(true);
    expect(input.value).toBe('keep local state');
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    await expect.poll(() => document.querySelector('input[aria-label="Detail local state"]')).toBeNull();
  } finally {
    wrapper.unmount();
  }
});

it('edits and applies schema conditions through the actual shared query UI', async () => {
  await page.viewport(1400, 900);
  const source = { ...readWorkspaceFixture(), querySchema: readQuerySchema() };
  const query = vi.spyOn(source, 'query');
  const wrapper = mount(ReadonlyRecordWorkspace, {
    attachTo: document.body,
    props: { view: source },
    attrs: { style: 'height:700px;width:100%' },
  });
  try {
    await expect.element(page.getByRole('button', { name: /高级筛选/ })).toBeEnabled();
    await page.getByRole('button', { name: /高级筛选/ }).click();
    await page.getByRole('textbox', { name: '事项', exact: true }).fill('needle');
    const requests = query.mock.calls.length;
    await page.getByRole('button', { name: /高级筛选/ }).click();
    await page.getByRole('button', { name: /高级筛选/ }).click();
    await expect.element(page.getByRole('textbox', { name: '事项', exact: true })).toHaveValue('needle');
    expect(query).toHaveBeenCalledTimes(requests);
    await page.getByRole('button', { name: '应用条件', exact: true }).click();
    await expect
      .poll(() => query.mock.calls.at(-1)?.[0].criteria)
      .toEqual({
        kind: 'GROUP',
        operator: 'AND',
        children: [{ kind: 'CONDITION', fieldName: 'title', operator: 'EQ', values: ['needle'] }],
      });
    await expect.element(page.getByText('已应用 1 条筛选', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: /排序方式/ }).click();
    await page.getByText('事项降序', { exact: true }).click();
    await expect.poll(() => query.mock.calls.at(-1)?.[0].sorts).toEqual([{ field: 'title', desc: true }]);
    await page.getByRole('button', { name: '重置', exact: true }).click();
    await expect.poll(() => query.mock.calls.at(-1)?.[0].criteria).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});
