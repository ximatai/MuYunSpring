import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import UiDatePicker from '@/vue-ui-antdv/components/UiDatePicker.vue';
import 'ant-design-vue/dist/reset.css';

it('confirms typed calendar time without changing wall-clock fields and supports clearing', async () => {
  const wrapper = mount(UiDatePicker, {
    props: { value: '2026-03-08T01:00:00', mode: 'datetime', ariaLabel: '业务时间' },
    attachTo: document.body,
  });
  try {
    const input = page.getByRole('textbox', { name: '业务时间' });
    await input.click();
    await input.fill('2026-03-08 02:30:17');
    await userEvent.keyboard('{Enter}');
    await expect.poll(() => wrapper.emitted('update:value')?.at(-1)).toEqual(['2026-03-08T02:30:17']);
    expect(wrapper.emitted('keydown')).toBeUndefined();
    await wrapper.setProps({ value: '2026-03-08T02:30:17' });
    await page.elementLocator(wrapper.get('.ant-picker-clear').element).click();
    await expect.poll(() => wrapper.emitted('update:value')?.at(-1)).toEqual(['']);
  } finally {
    wrapper.unmount();
  }
});

it('selects a date from the calendar rather than exposing a native date input', async () => {
  const wrapper = mount(UiDatePicker, {
    props: { value: '2026-10-20', ariaLabel: '业务日期' },
    attachTo: document.body,
  });
  try {
    await page.getByRole('textbox', { name: '业务日期' }).click();
    // Ant's test build treats every mousedown as outside the picker. Activate the
    // rendered cell directly; the production pointer path is verified on the live page.
    const cell = document.querySelector(
      '.ui-date-picker-popup:not(.ant-picker-dropdown-hidden) td[title="2026-10-21"]',
    ) as HTMLElement;
    cell.click();
    await expect.poll(() => wrapper.emitted('update:value')?.at(-1)).toEqual(['2026-10-21']);
    expect(wrapper.find('input[type="date"]').exists()).toBe(false);
  } finally {
    wrapper.unmount();
  }
});

it('keeps the date and time panels usable inside a narrow viewport', async () => {
  await page.viewport(390, 844);
  const wrapper = mount(UiDatePicker, {
    props: { value: '2026-10-20T14:15:37', mode: 'datetime', ariaLabel: '窄屏时间' },
    attachTo: document.body,
  });
  try {
    await page.getByRole('textbox', { name: '窄屏时间' }).click();
    await expect
      .poll(
        () =>
          document
            .querySelector(
              '.ui-date-picker-popup:not(.ant-picker-dropdown-hidden) .ant-picker-datetime-panel',
            )
            ?.getBoundingClientRect().width,
      )
      .toBeLessThanOrEqual(366);
    const panel = document.querySelector(
      '.ui-date-picker-popup:not(.ant-picker-dropdown-hidden) .ant-picker-panel-container',
    )!;
    await expect.poll(() => panel.getBoundingClientRect().left).toBeGreaterThanOrEqual(0);
    const rect = panel.getBoundingClientRect();
    expect(rect.left).toBeGreaterThanOrEqual(0);
    expect(rect.right).toBeLessThanOrEqual(390);
    expect(panel.querySelector('.ant-picker-time-panel')).not.toBeNull();
    expect(panel.querySelector('.ant-picker-ok')).not.toBeNull();
  } finally {
    wrapper.unmount();
    await page.viewport(1004, 804);
  }
});
