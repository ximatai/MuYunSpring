import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import CronExpressionInput from '@/platform-components/CronExpressionInput.vue';
import '@/vue-ui-antdv/styles.css';
import '@/styles.css';

it.each([1063, 390])(
  'generates a weekly plan only after Apply and keeps the popover usable at %s',
  async (width) => {
    await page.viewport(width, 804);
    const validator = vi.fn().mockResolvedValue({ valid: true });
    const wrapper = mount(CronExpressionInput, {
      props: { value: '0 0 9 ? * MON#2', validator },
      attrs: { style: 'width: 310px; margin: 20px' },
      attachTo: document.body,
    });
    try {
      await flushPromises();
      await page.getByRole('button', { name: '计划助手', exact: true }).click();
      await expect
        .element(page.getByText('当前为自定义表达式。助手只在点击“应用计划”后替换原值。'))
        .toBeVisible();
      await selectField('执行周期');
      await page.getByText('每周', { exact: true }).click();
      await selectField('星期');
      await page.getByText('周三', { exact: true }).click();
      await userEvent.keyboard('{Escape}');
      expect(wrapper.emitted('update:value')).toBeUndefined();
      const panel = document.querySelector('.cron-assistant')!.getBoundingClientRect();
      expect(panel.left).toBeGreaterThanOrEqual(0);
      expect(panel.right).toBeLessThanOrEqual(width);
      await page.getByRole('button', { name: '应用计划', exact: true }).click();
      await expect.poll(() => wrapper.emitted('update:value')?.at(-1)).toEqual(['0 0 0 * * MON,WED']);
      expect(validator).toHaveBeenLastCalledWith('0 0 0 * * MON,WED');
      expect(wrapper.get('input').element.value).toBe('0 0 0 * * MON,WED');
      expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(width);
    } finally {
      wrapper.unmount();
    }
  },
);

it('validates a manually entered expression on blur with a visible failure message', async () => {
  const validator = vi.fn().mockResolvedValue({ valid: true });
  const wrapper = mount(CronExpressionInput, {
    props: { value: '0 0 9 * * *', validator },
    attachTo: document.body,
  });
  try {
    await flushPromises();
    validator.mockResolvedValue({ valid: false, reason: 'MINUTE_PRECISION_REQUIRED' });
    await page.getByRole('textbox', { name: 'Cron 表达式', exact: true }).fill('*/2 * * * * *');
    await page.getByRole('button', { name: '计划助手', exact: true }).click();
    await expect.element(page.getByText('平台调度精度为分钟，请将秒位固定为 0。')).toBeVisible();
    expect(wrapper.get('[role="group"]').attributes('aria-invalid')).toBe('true');
    await page.getByRole('button', { name: /取\s*消/ }).click();
    expect(wrapper.get('input').element.value).toBe('*/2 * * * * *');
  } finally {
    wrapper.unmount();
  }
});

async function selectField(name: string) {
  const label = [...document.querySelectorAll('.cron-assistant label')].find((element) =>
    element.textContent?.trim().startsWith(name),
  );
  await page.elementLocator(label!.querySelector('.ant-select-selector')!).click();
}
