import { h } from 'vue';
import UiTree from '@/vue-ui-antdv/components/UiTree.vue';
import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import { UiButton } from '@muyun/vue-ui-antdv';
import RecordExplorerPanel from '@/platform-components/RecordExplorerPanel.vue';
import '@/styles.css';
import 'ant-design-vue/dist/reset.css';

it('keeps selected search hover styling aligned with the shared button adapter', async () => {
  const panel = mount(RecordExplorerPanel, {
    attachTo: document.body,
    props: { title: '应用列表' },
  });
  const reference = mount(UiButton, {
    attachTo: document.body,
    props: { type: 'text', iconOnly: true, size: 'small', selected: true },
  });
  const search = panel.get('button[title="搜索应用列表"]');

  await search.trigger('click');
  await page.elementLocator(search.element).hover();
  await Promise.all(search.element.getAnimations().map((animation: Animation) => animation.finished));
  const searchStyle = getComputedStyle(search.element);
  const searchColor = searchStyle.color;
  const searchBackgroundColor = searchStyle.backgroundColor;
  const searchBorderTopColor = searchStyle.borderTopColor;

  await page.elementLocator(reference.element).hover();

  await Promise.all(reference.element.getAnimations().map((animation: Animation) => animation.finished));
  const referenceStyle = getComputedStyle(reference.element);
  expect(searchColor).toBe(referenceStyle.color);
  expect(searchBackgroundColor).toBe(referenceStyle.backgroundColor);
  expect(searchBorderTopColor).toBe(referenceStyle.borderTopColor);
});

it.each(['tree', 'flat'] as const)(
  'scrolls a long %s inside the explorer without moving its header',
  async (displayMode) => {
    const panel = mount(RecordExplorerPanel, {
      attachTo: document.body,
      props: { title: '长导航', searchable: false },
      attrs: { style: 'height: 240px; width: 360px;' },
      slots: {
        default: () =>
          h(UiTree, {
            displayMode,
            nodes: Array.from({ length: 60 }, (_, index) => ({
              key: `node-${index}`,
              title: `节点 ${index}`,
              isLeaf: true,
            })),
          }),
      },
    });
    try {
      const tree = panel.get('.ui-tree').element as HTMLElement;
      const header = panel.get('.record-explorer-panel-header').element;
      const top = header.getBoundingClientRect().top;
      expect(tree.scrollHeight).toBeGreaterThan(tree.clientHeight);
      expect(getComputedStyle(tree).overflowY).toBe('auto');
      await page.elementLocator(panel.get('[data-ui-tree-key="node-59"]').element).click();
      expect(tree.scrollTop).toBeGreaterThan(0);
      expect(header.getBoundingClientRect().top).toBe(top);
      const last = panel.get('[data-ui-tree-key="node-59"]').element.getBoundingClientRect();
      expect(last.bottom).toBeLessThanOrEqual(tree.getBoundingClientRect().bottom + 1);
    } finally {
      panel.unmount();
    }
  },
);

it('keeps protection tags visible when nested tree labels are long', async () => {
  const panel = mount(RecordExplorerPanel, {
    attachTo: document.body,
    props: { title: '数据模型', searchable: false },
    attrs: { style: 'height: 240px; width: 280px;' },
    slots: {
      default: () =>
        h(UiTree, {
          expandedKeys: ['main', 'child'],
          nodes: [
            {
              key: 'main',
              title: '主实体',
              children: [
                {
                  key: 'child',
                  title: '子实体',
                  children: [
                    {
                      key: 'foreign-key',
                      title: '元数据闭环验证关联字段的长名称',
                      secondary: 'metadataSmoke20260901Id',
                      tag: '受保护',
                      muted: true,
                    },
                  ],
                },
              ],
            },
          ],
        }),
    },
  });
  const tag = panel.get('.ui-record-explorer-item-tag').element;
  const item = tag.closest('.ui-record-explorer-item')!;
  await expect.poll(() => tag.getBoundingClientRect().width).toBeGreaterThan(0);
  const tagBounds = tag.getBoundingClientRect();
  const bounds = item.getBoundingClientRect();
  expect(tagBounds.right).toBeLessThanOrEqual(bounds.right);
  expect(tagBounds.left).toBeGreaterThanOrEqual(bounds.left);
  expect(tagBounds.right).toBeLessThanOrEqual(panel.element.getBoundingClientRect().right);
  const title = item.querySelector('.ui-record-explorer-item-title')!;
  expect(title.scrollWidth).toBeGreaterThan(title.clientWidth);
  panel.unmount();
});
