import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import type { ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';
import PasswordSecurityRules from '@/views/PasswordSecurityRules.vue';
import '@/styles.css';
import '@muyun/vue-ui-antdv/styles.css';

it.each([520, 1000])('keeps creation above the rules and row operations compact at %ipx', async (width) => {
  await page.viewport(width, 800);
  const host = document.createElement('div');
  host.style.width = `${width - 32}px`;
  document.body.append(host);
  const row = {
    id: 'r1',
    version: 1,
    title: '密码长度',
    pattern: '^.{6,}$',
    message: '密码长度不能少于 6 位',
    sortOrder: 10,
    enabled: true,
  };
  const wrapper = mount(PasswordSecurityRules, {
    attachTo: host,
    props: {
      rows: [row],
      originalRows: [row],
      editing: true,
      disabled: false,
      targetContext: { can: () => true } as unknown as ModuleContext<QueryListRecord>,
    },
  });
  try {
    const create = wrapper.get('.record-query-list-header button').element.getBoundingClientRect();
    const table = wrapper.get('table').element.getBoundingClientRect();
    const actions = wrapper.get('.rule-actions').element.getBoundingClientRect();
    expect(create.bottom).toBeLessThanOrEqual(table.top);
    expect(actions.height).toBeLessThan(40);
    await expect
      .poll(() => wrapper.get('.rule-actions').element.getBoundingClientRect().right)
      .toBeLessThanOrEqual(host.getBoundingClientRect().right + 1);
    await wrapper.get('.rule-actions button[title="更多"]').trigger('click');
    await expect.element(page.getByRole('menuitem', { name: '停用' })).toBeVisible();
    await expect.element(page.getByRole('menuitem', { name: '删除' })).toBeVisible();
    expect(wrapper.emitted('update:rows')).toBeUndefined();
  } finally {
    wrapper.unmount();
    host.remove();
  }
});
