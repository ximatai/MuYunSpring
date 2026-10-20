import { mount } from '@vue/test-utils';
import { defineComponent, h, inject, ref } from 'vue';
import { expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import ManagementWorkspace from '@/platform-components/ManagementWorkspace.vue';
import ManagementExplorerColumn from '@/platform-components/ManagementExplorerColumn.vue';
import RecordDetailPanel from '@/platform-components/RecordDetailPanel.vue';
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
  'fits a two-navigator list in a %spx host and releases collapsed rows',
  async (width) => {
    await page.viewport(1600, 900);
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
            h('div', { style: `width:${width}px;height:520px` }, [
              h(ManagementWorkspace, { explorerCount: 2, listSurface: true }, () => [
                ...['租户', '模块'].map((title) =>
                  h(ManagementExplorerColumn, { title, collapsible: true }, () => h(navigator, { title })),
                ),
                h('section', { class: 'test-primary', style: 'min-width:0;min-height:0' }, [
                  h('button', '新增规则'),
                ]),
              ]),
            ]);
        },
      }),
      { attachTo: document.body },
    );
    try {
      const primary = wrapper.get('.test-primary').element;
      const host = wrapper.get('.management-workspace').element;
      const assertFits = () => {
        const bounds = primary.getBoundingClientRect();
        const container = host.getBoundingClientRect();
        expect(bounds.right).toBeLessThanOrEqual(container.right + 1);
        expect(bounds.bottom).toBeLessThanOrEqual(container.bottom + 1);
        expect(bounds.width).toBeGreaterThan(200);
        expect(bounds.height).toBeGreaterThan(150);
      };
      assertFits();
      for (const title of ['租户', '模块']) {
        await page.getByRole('button', { name: `收起${title}`, exact: true }).click();
        await expect
          .poll(() => wrapper.findAll('.management-explorer-column--collapsed').length)
          .toBe(title === '租户' ? 1 : 2);
        await expect
          .poll(() => primary.getBoundingClientRect().right)
          .toBeLessThanOrEqual(host.getBoundingClientRect().right + 1);
        await expect
          .poll(() => primary.getBoundingClientRect().bottom)
          .toBeLessThanOrEqual(host.getBoundingClientRect().bottom + 1);
        assertFits();
      }
      await page.getByRole('button', { name: '新增规则', exact: true }).click();
    } finally {
      wrapper.unmount();
    }
  },
);
