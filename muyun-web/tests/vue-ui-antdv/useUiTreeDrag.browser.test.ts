import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import { expect, it } from 'vitest';
import { commands } from 'vitest/browser';
import UiTree from '@/vue-ui-antdv/components/UiTree.vue';
import UiDataTable from '@/vue-ui-antdv/components/UiDataTable.vue';
import { useUiDropTarget } from '@/vue-ui-antdv/useUiTreeDrag';
import type { UiTreeDropEvent } from '@/vue-ui-antdv/types';
import 'ant-design-vue/dist/reset.css';

it.each(['visibility: hidden', 'display: none'])(
  'moves keyboard dragging from A to C past a collapsed B using %s',
  async (hiddenStyle) => {
    const drops: UiTreeDropEvent[] = [];
    const Fixture = defineComponent({
      setup() {
        const root = ref<HTMLElement>();
        useUiDropTarget(root, {
          resolve: () => ({
            instanceId: 'preview',
            kind: 'node',
            node: { key: 'preview-field', title: '预览字段' },
            position: 'before',
          }),
          allow: () => true,
          drop: (event) => drops.push(event),
        });
        return () =>
          h('div', [
            h(UiTree, { nodes: [{ key: 'source', title: '可用字段' }], draggable: true }),
            h('div', { style: hiddenStyle }, [
              h(UiTree, { nodes: [{ key: 'hidden', title: '已收起的页面结构' }], draggable: true }),
            ]),
            h('div', { ref: root, tabindex: 0, 'data-ui-drop-key': 'preview-field' }, '预览字段'),
          ]);
      },
    });
    const wrapper = mount(Fixture, { attachTo: document.body });
    try {
      await nextTick();
      const source = wrapper.get('[data-ui-tree-key="source"]').element as HTMLElement;
      source.focus();
      source.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
      source.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
      expect(document.activeElement).toBe(wrapper.get('[data-ui-drop-key="preview-field"]').element);
      document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      expect(drops).toHaveLength(1);
      expect(drops[0].target).toMatchObject({ kind: 'node', node: { key: 'preview-field' } });
    } finally {
      wrapper.unmount();
    }
  },
);

it.each(['right', 'left'] as const)(
  'scrolls wide columns toward the %s edge and resolves the newly visible column at release',
  async (edge) => {
    const drops: UiTreeDropEvent[] = [];
    const Fixture = defineComponent({
      setup() {
        const root = ref<HTMLElement>();
        useUiDropTarget(root, {
          resolve(origin, _y, _position, _source, x) {
            const heading = origin.closest('th')?.querySelector<HTMLElement>('[data-column]');
            if (!heading) return;
            const rect = heading.getBoundingClientRect();
            return {
              instanceId: 'wide-table',
              kind: 'node',
              node: { key: heading.dataset.column!, title: heading.textContent ?? '' },
              position: (x ?? 0) < rect.left + rect.width / 2 ? 'before' : 'after',
            };
          },
          allow: () => true,
          drop: (event) => drops.push(event),
        });
        return () =>
          h('div', { id: 'wide-drag-fixture', style: 'width: 360px' }, [
            h(UiTree, { nodes: [{ key: 'source', title: '拖动列' }], draggable: true }),
            h('div', { ref: root }, [
              h(
                UiDataTable,
                {
                  columns: Array.from({ length: 8 }, (_, index) => ({
                    key: `column-${index}`,
                    title: `列 ${index}`,
                    width: '180px',
                  })),
                  rows: [{ id: 'sample' }],
                  rowKey: 'id',
                  pagination: false,
                  horizontalScroll: true,
                },
                {
                  header: ({ column }: { column: { key: string; title: string } }) =>
                    h('span', { 'data-column': column.key }, column.title),
                },
              ),
            ]),
          ]);
      },
    });
    const wrapper = mount(Fixture, { attachTo: document.body });
    try {
      await nextTick();
      const scrollport = wrapper.get('.ant-table-content').element as HTMLElement;
      await expect.poll(() => scrollport.scrollWidth - scrollport.clientWidth).toBeGreaterThan(500);
      const maximum = scrollport.scrollWidth - scrollport.clientWidth;
      if (edge === 'left') scrollport.scrollLeft = maximum;
      await commands.treeScrollGesture(
        '#wide-drag-fixture [data-ui-tree-key="source"]',
        '#wide-drag-fixture .ant-table-content',
        edge,
      );
      await expect.poll(() => scrollport.scrollLeft, { timeout: 5000 }).toBe(edge === 'right' ? maximum : 0);
      await commands.treeRelease();
      expect(drops).toHaveLength(1);
      expect(drops[0].target).toMatchObject({
        kind: 'node',
        node: { key: edge === 'right' ? 'column-7' : 'column-0' },
        position: edge === 'right' ? 'after' : 'before',
      });
    } finally {
      await commands.treeRelease();
      wrapper.unmount();
    }
  },
);

it('keeps an external drag alive when entering an action bar nested in a preview', async () => {
  const drops: string[] = [];
  const Fixture = defineComponent({
    setup() {
      const outer = ref<HTMLElement>();
      const inner = ref<HTMLElement>();
      for (const [root, key, cancel] of [
        [outer, 'preview', true],
        [inner, 'actions', false],
      ] as const) {
        useUiDropTarget(root, {
          cancelWhenPointerLeaves: cancel,
          resolve: () => ({ instanceId: key, kind: 'node', node: { key, title: key }, position: 'inside' }),
          allow: () => true,
          drop: () => drops.push(key),
        });
      }
      return () =>
        h(
          'div',
          { style: 'position: fixed; top: 0; left: 0; z-index: 99999; width: 400px; background: white' },
          [
            h(UiTree, { nodes: [{ key: 'source', title: '自定义动作' }], draggable: true }),
            h('div', { ref: outer, 'data-test': 'outer', style: 'padding: 40px; width: 300px' }, [
              h('div', { ref: inner, 'data-test': 'inner', style: 'height: 40px' }, '表单动作'),
            ]),
          ],
        );
    },
  });
  const wrapper = mount(Fixture, { attachTo: document.body });
  try {
    await nextTick();
    await commands.treeGesture('[data-ui-tree-key="source"]', '[data-test="outer"]', 0.05, 'hold', 0.05);
    await commands.treeMove('[data-test="inner"]');
    await commands.treeRelease();
    expect(drops).toEqual(['actions']);
  } finally {
    wrapper.unmount();
  }
});
