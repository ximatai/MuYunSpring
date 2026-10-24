import { mount } from '@vue/test-utils';
import { defineComponent, h, inject, ref } from 'vue';
import { expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import ManagementWorkspace from '@/platform-components/ManagementWorkspace.vue';
import ManagementExplorerColumn from '@/platform-components/ManagementExplorerColumn.vue';
import RecordDetailPanel from '@/platform-components/RecordDetailPanel.vue';
import RecordExplorerPanel from '@/platform-components/RecordExplorerPanel.vue';
import '@/styles.css';
import { providePageLayout } from '@/platform-components/pageLayoutContext';
import { MANAGEMENT_EXPLORER_COLUMN_CONTEXT } from '@/platform-components/managementExplorerContext';
import { useWorkspaceSortActivity } from '@/platform-components/managementWorkspaceContext';

it('locks mouse and keyboard navigation locally and restores it when editing ends', async () => {
  const selected = vi.fn();
  const editing = ref(false);
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h('div', [
          h(ManagementWorkspace, { editing: editing.value }, () => [
            h(ManagementExplorerColumn, {}, () => [
              h('button', { onClick: selected }, '选择节点'),
              h('input', { 'aria-label': '搜索导航' }),
            ]),
            h(RecordDetailPanel, { title: '编辑' }, () => [
              h('input', { 'aria-label': '名称' }),
              h(
                'button',
                {
                  onClick: () => {
                    editing.value = !editing.value;
                  },
                },
                '切换编辑',
              ),
            ]),
          ]),
          h(ManagementWorkspace, {}, () =>
            h(ManagementExplorerColumn, {}, () => h('button', { onClick: selected }, '独立导航')),
          ),
        ]),
    }),
    { attachTo: document.body },
  );
  try {
    await page.getByRole('button', { name: '选择节点' }).click();
    expect(selected).toHaveBeenCalledTimes(1);
    await page.getByRole('button', { name: '切换编辑' }).click();
    const explorer = wrapper.get('.management-explorer-column').element as HTMLElement;
    expect(explorer.inert).toBe(true);
    const button = explorer.querySelector('button')!;
    button.focus();
    expect(document.activeElement).not.toBe(button);
    await expect(
      page.getByRole('button', { name: '选择节点', includeHidden: true }).click({ timeout: 150 }),
    ).rejects.toThrow();
    await page.getByRole('textbox', { name: '名称' }).fill('草稿');
    await page.getByRole('button', { name: '独立导航' }).click();
    expect(selected).toHaveBeenCalledTimes(2);
    await page.getByRole('button', { name: '切换编辑' }).click();
    await page.getByRole('button', { name: '选择节点' }).click();
    expect(selected).toHaveBeenCalledTimes(3);
  } finally {
    wrapper.unmount();
  }
});

it('keeps detail actions visible and locks only the workspace with a pending sort', async () => {
  const pending = ref(false);
  const source = defineComponent({
    setup() {
      useWorkspaceSortActivity(pending);
      return () => h('button', '排序导航');
    },
  });
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h('div', [
          h(ManagementWorkspace, {}, () => [
            h(ManagementExplorerColumn, {}, () => h(source)),
            h(RecordDetailPanel, { title: '当前详情' }, { actions: () => h('button', '新增字段') }),
          ]),
          h(ManagementWorkspace, {}, () =>
            h(RecordDetailPanel, { title: '其他详情' }, { actions: () => h('button', '其他操作') }),
          ),
        ]),
    }),
    { attachTo: document.body },
  );
  try {
    const detail = wrapper.findAll('.record-detail-panel-region')[0]!.element as HTMLElement;
    const other = wrapper.findAll('.record-detail-panel-region')[1]!.element as HTMLElement;
    expect(detail.inert).toBe(false);
    pending.value = true;
    await expect.poll(() => detail.inert).toBe(true);
    expect(detail.textContent).toContain('新增字段');
    expect(other.inert).toBe(false);
    expect((wrapper.get('.management-explorer-column').element as HTMLElement).inert).toBe(true);
    pending.value = false;
    await expect.poll(() => detail.inert).toBe(false);
    await page.getByRole('button', { name: '新增字段' }).click();
  } finally {
    wrapper.unmount();
  }
});

it.each([1280, 1004, 600])(
  'fits the primary surface and preserves manual folds in a %spx host',
  async (width) => {
    await page.viewport(1600, 900);
    const hostWidth = ref(width);
    const navigator = defineComponent({
      props: { title: String },
      setup(props) {
        const explorer = inject(MANAGEMENT_EXPLORER_COLUMN_CONTEXT)!;
        return () => h('button', { onClick: explorer.collapse }, `收起${props.title}`);
      },
    });
    const wrapper = mount(
      defineComponent({
        setup() {
          providePageLayout('workspace');
          return () =>
            h('div', { style: `width:${hostWidth.value}px;height:520px` }, [
              h(ManagementWorkspace, { explorerCount: 2 }, () => [
                ...['租户', '模块'].map((title) =>
                  h(ManagementExplorerColumn, { title, collapsible: true }, () => h(navigator, { title })),
                ),
                h(
                  'section',
                  { class: 'test-primary', style: 'min-width:0;min-height:0' },
                  h('button', '新增规则'),
                ),
              ]),
            ]);
        },
      }),
      { attachTo: document.body },
    );
    try {
      const host = wrapper.get<HTMLElement>('.management-workspace').element;
      const primary = wrapper.get('.test-primary').element;
      const folded = () => wrapper.findAll('.management-explorer-column--collapsed');
      await expect.poll(() => folded().length).toBe(width >= 1144 ? 0 : width >= 900 ? 1 : 2);
      expect(host.scrollWidth).toBeLessThanOrEqual(host.clientWidth + 1);
      expect(primary.getBoundingClientRect().width).toBeGreaterThanOrEqual(Math.min(560, width - 48));
      if (width < 1144) {
        await page.getByRole('button', { name: '展开租户', exact: true }).click();
        await expect.poll(() => folded().length).toBe(width >= 900 ? 0 : 1);
        if (width === 600) expect(host.scrollWidth).toBeGreaterThan(host.clientWidth);
      }
      hostWidth.value = 1280;
      await expect.poll(() => folded().length).toBe(0);
      await page.getByRole('button', { name: '收起租户', exact: true }).click();
      await expect.poll(() => folded().length).toBe(1);
      hostWidth.value = 600;
      await expect.poll(() => folded().length).toBe(2);
      hostWidth.value = 1280;
      await expect.poll(() => folded().length).toBe(1);
      expect(folded()[0]!.text()).toContain('租户');
      await page.getByRole('button', { name: '展开租户', exact: true }).click();
      await expect.poll(() => folded().length).toBe(0);
      await page.getByRole('button', { name: '新增规则', exact: true }).click();
    } finally {
      wrapper.unmount();
    }
  },
);

it('preserves independent manual expansion and collapse across resizes and resets on a new page session', async () => {
  await page.viewport(900, 800);
  const Harness = defineComponent({
    setup() {
      providePageLayout('workspace');
      return () =>
        h('div', { style: 'width:calc(100vw - 40px);height:520px' }, [
          h(ManagementWorkspace, { explorerCount: 2, listSurface: true }, () => [
            ...['租户', '项目'].map((title) =>
              h(ManagementExplorerColumn, { title, collapsible: true }, () =>
                h(RecordExplorerPanel, { title }, () => h('button', `选择${title}`)),
              ),
            ),
            h('main', { class: 'test-primary', style: 'min-width:0' }, h('button', '业务列表')),
          ]),
        ]);
    },
  });
  const wrapper = mount(Harness, { attachTo: document.body });
  try {
    const columns = wrapper.findAll('.management-explorer-column');
    const folded = () => wrapper.findAll('.management-explorer-column--collapsed');
    const host = wrapper.get<HTMLElement>('.management-workspace').element;
    const primary = wrapper.get<HTMLElement>('.test-primary').element;
    const isInline = (index: number) => getComputedStyle(columns[index]!.element).position === 'relative';
    await expect.poll(() => folded().length).toBe(2);
    expect(host.scrollHeight).toBeLessThanOrEqual(host.clientHeight + 1);
    await page.getByRole('button', { name: '展开租户', exact: true }).click();
    await expect.poll(() => folded().length).toBe(1);
    expect(isInline(0)).toBe(true);
    await expect.poll(() => host.scrollWidth).toBeGreaterThan(host.clientWidth);
    expect(Math.round(primary.getBoundingClientRect().width)).toBeGreaterThanOrEqual(720);
    await expect
      .poll(() => primary.getBoundingClientRect().left - columns[0]!.element.getBoundingClientRect().right)
      .toBeGreaterThanOrEqual(0);
    await page.getByRole('button', { name: '展开项目', exact: true }).click();
    await expect.poll(() => folded().length).toBe(0);
    expect(isInline(0) && isInline(1)).toBe(true);
    await expect
      .poll(
        () =>
          columns[1]!.element.getBoundingClientRect().left -
          columns[0]!.element.getBoundingClientRect().right,
      )
      .toBeGreaterThanOrEqual(0);
    await expect
      .poll(() => primary.getBoundingClientRect().left - columns[1]!.element.getBoundingClientRect().right)
      .toBeGreaterThanOrEqual(0);

    for (const width of [620, 1500, 620]) {
      await page.viewport(width, 800);
      await expect.poll(() => host.clientWidth).toBe(width - 40);
      expect(folded()).toHaveLength(0);
      expect(isInline(0) && isInline(1)).toBe(true);
      expect(Math.round(primary.getBoundingClientRect().width)).toBeGreaterThanOrEqual(720);
    }
    await page.getByRole('button', { name: '业务列表', exact: true }).click();
    expect(host.scrollLeft).toBeGreaterThan(0);
    host.scrollLeft = 0;
    await page.getByRole('button', { name: '收起项目', exact: true }).click();
    await expect.poll(() => folded().length).toBe(1);
    expect(columns[0]!.classes()).not.toContain('management-explorer-column--collapsed');
    for (const width of [1500, 620]) {
      await page.viewport(width, 800);
      await expect.poll(() => host.clientWidth).toBe(width - 40);
      expect(folded()).toHaveLength(1);
      expect(columns[1]!.classes()).toContain('management-explorer-column--collapsed');
    }
    host.scrollLeft = 0;
    await page.getByRole('button', { name: '收起租户', exact: true }).click();
    await expect.poll(() => folded().length).toBe(2);
    await expect.poll(() => host.scrollHeight).toBeLessThanOrEqual(host.clientHeight + 1);
  } finally {
    wrapper.unmount();
  }
  const reopened = mount(Harness, { attachTo: document.body });
  try {
    await expect.poll(() => reopened.findAll('.management-explorer-column--collapsed').length).toBe(2);
    await page.viewport(1500, 800);
    await expect.poll(() => reopened.findAll('.management-explorer-column--collapsed').length).toBe(0);
  } finally {
    reopened.unmount();
  }
});
