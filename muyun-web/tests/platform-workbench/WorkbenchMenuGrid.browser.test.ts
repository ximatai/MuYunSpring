import { flushPromises, mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import WorkbenchMenu from '@/platform-workbench/WorkbenchMenu.vue';
import type { MenuTreeNode } from '@/web-contracts';
import '@/styles.css';

const titles = ['平台建模', '组织与权限', '业务支撑', '日志管理', '运行运维', '平台设置', '教学演示'];
const counts = [4, 7, 3, 4, 1, 4, 6];
const leaf = (id: string): MenuTreeNode => ({
  record: {
    id,
    title: id,
    schemeId: 'default',
    entryType: 'module',
    openMode: 'tab',
    moduleAlias: `test.${id}`,
  },
  children: [],
});
function menus(tall = false): MenuTreeNode[] {
  return [
    {
      record: { id: 'platform', title: '平台管理', schemeId: 'default' },
      children: titles.map((title, index) => ({
        record: { id: `group-${index}`, title, schemeId: 'default' },
        children: Array.from({ length: tall && index === 1 ? 30 : counts[index]! }, (_, entry) =>
          leaf(`entry-${index}-${entry}`),
        ),
      })),
    },
  ];
}

it.each([1004, 720])('balances consecutive groups in configured column order at %ipx', async (width) => {
  await page.viewport(width, 804);
  const wrapper = mount(WorkbenchMenu, {
    attachTo: document.body,
    props: { menus: menus(), presentation: 'compact', compactOpen: true },
  });
  try {
    await page.elementLocator(wrapper.get('.root-menu-item').element).click();
    await flushPromises();
    const groups = wrapper.findAll('.mega-group');
    expect(groups.map((group) => group.get('.mega-group-title').text())).toEqual(titles);
    const columns = wrapper.findAll('.mega-column');
    expect(columns).toHaveLength(width === 1004 ? 3 : 2);
    const heights = columns.map((column) => column.element.getBoundingClientRect().height);
    expect(Math.max(...heights) - Math.min(...heights)).toBeLessThan(110);
    for (const column of columns) {
      const entries = column.findAll('.mega-group').map((group) => group.element.getBoundingClientRect());
      for (let index = 1; index < entries.length; index++) {
        expect(entries[index]!.left).toBe(entries[0]!.left);
        expect(entries[index]!.top).toBeGreaterThan(entries[index - 1]!.bottom);
      }
    }
    const panel = wrapper.get('.mega-panel').element.getBoundingClientRect();
    expect(panel.height).toBeLessThanOrEqual(620);
    expect(panel.right).toBeLessThanOrEqual(width);
    expect(panel.bottom).toBeLessThanOrEqual(804);
  } finally {
    wrapper.unmount();
  }
});

it('bounds tall groups to the viewport and keeps deep navigation reachable outside the scrolling grid', async () => {
  await page.viewport(1004, 400);
  const nodes = menus(true);
  nodes[0]!.children![0]!.children![0]!.children = [leaf('deep-leaf')];
  const wrapper = mount(WorkbenchMenu, {
    attachTo: document.body,
    props: { menus: nodes, presentation: 'compact', compactOpen: true },
  });
  try {
    await page.elementLocator(wrapper.get('.root-menu-item').element).click();
    await expect
      .poll(() => wrapper.get('.mega-panel').element.getBoundingClientRect().bottom)
      .toBeLessThanOrEqual(400);
    const grid = wrapper.get('.mega-groups').element;
    expect(grid.scrollHeight).toBeGreaterThan(grid.clientHeight);
    await page.getByRole('button', { name: '展开entry-0-0下级菜单', exact: true }).click();
    await expect.poll(() => wrapper.find('.mega-deep-panel').exists()).toBe(true);
    const panel = wrapper.get('.mega-panel').element.getBoundingClientRect();
    const deep = wrapper.get('.mega-deep-panel').element.getBoundingClientRect();
    expect(deep.height).toBeGreaterThan(0);
    expect(deep.bottom).toBeLessThanOrEqual(panel.bottom);
    await page.getByRole('button', { name: 'deep-leaf', exact: true }).click();
    expect(wrapper.emitted('selectMenu')?.at(-1)?.[0]).toMatchObject({ id: 'deep-leaf' });
  } finally {
    wrapper.unmount();
  }
});

it('keeps expanded phone navigation in a single column with document scrolling', async () => {
  await page.viewport(375, 804);
  const wrapper = mount(WorkbenchMenu, {
    attachTo: document.body,
    props: { menus: menus(), presentation: 'expanded' },
  });
  try {
    await page.elementLocator(wrapper.get('.root-menu-item').element).click();
    const groups = wrapper.findAll('.mega-group').map((group) => group.element.getBoundingClientRect());
    expect(new Set(groups.map((group) => group.left)).size).toBe(1);
    expect(getComputedStyle(wrapper.get('.mega-panel').element).maxHeight).toBe('none');
  } finally {
    wrapper.unmount();
  }
});
