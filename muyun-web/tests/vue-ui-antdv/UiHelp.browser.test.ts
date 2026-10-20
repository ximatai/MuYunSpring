import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { UiHelp, UiPopover, UiButton } from '@muyun/vue-ui-antdv';
import '@/styles.css';

it.each([1004, 390])('keeps help compact and keyboard accessible at %s pixels', async (width) => {
  await page.viewport(width, 804);
  let escaped = 0;
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h(
          'div',
          {
            onKeydown: (event: KeyboardEvent) => {
              if (event.key === 'Escape') escaped++;
            },
          },
          [h(UiHelp, { label: '配置说明' }, () => '这是一段仅在需要时展开的配置说明，保留完整的业务含义。')],
        ),
    }),
    { attachTo: document.body },
  );
  try {
    const trigger = wrapper.get('button').element;
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(document.querySelector('.ui-help-content')).toBeNull();
    trigger.focus();
    await userEvent.keyboard('{Enter}');
    await expect
      .element(page.getByText('这是一段仅在需要时展开的配置说明，保留完整的业务含义。'))
      .toBeVisible();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    await expect
      .poll(() => document.querySelector('.ui-help-content')!.getBoundingClientRect().left)
      .toBeGreaterThanOrEqual(0);
    const box = document.querySelector('.ui-help-content')!.getBoundingClientRect();
    expect(box.right).toBeLessThanOrEqual(width);
    expect(box.left).toBeGreaterThanOrEqual(0);
    await userEvent.keyboard('{Escape}');
    await expect
      .element(page.getByText('这是一段仅在需要时展开的配置说明，保留完整的业务含义。'))
      .not.toBeVisible();
    expect(document.activeElement).toBe(trigger);
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(escaped).toBe(0);
  } finally {
    wrapper.unmount();
  }
});

it('retains uncontrolled popover behavior for existing callers', async () => {
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h(UiPopover, {}, { default: () => h(UiButton, {}, () => '已有入口'), content: () => '已有详情' }),
    }),
    { attachTo: document.body },
  );
  try {
    await page.getByRole('button', { name: '已有入口', exact: true }).click();
    await expect.element(page.getByText('已有详情', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '已有入口', exact: true }).click();
    await expect.element(page.getByText('已有详情', { exact: true })).not.toBeVisible();
  } finally {
    wrapper.unmount();
  }
});

it('returns focus to help after Escape from an action inside the explanation', async () => {
  const wrapper = mount(UiHelp, {
    attachTo: document.body,
    props: { label: '诊断说明' },
    slots: { default: () => h('button', {}, '重新读取') },
  });
  try {
    await page.getByRole('button', { name: '诊断说明', exact: true }).click();
    await page.getByRole('button', { name: '重新读取', exact: true }).click();
    await userEvent.keyboard('{Escape}');
    await expect.element(page.getByText('重新读取', { exact: true })).not.toBeVisible();
    expect(document.activeElement).toBe(wrapper.get('button').element);
  } finally {
    wrapper.unmount();
  }
});
