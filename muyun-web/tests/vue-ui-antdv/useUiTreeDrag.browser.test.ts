import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import { expect, it } from 'vitest';
import { commands } from 'vitest/browser';
import UiTree from '@/vue-ui-antdv/components/UiTree.vue';
import UiDataTable from '@/vue-ui-antdv/components/UiDataTable.vue';
import { useUiDropTarget } from '@/vue-ui-antdv/useUiTreeDrag';
import type { UiTreeDropEvent } from '@/vue-ui-antdv/types';
import 'ant-design-vue/dist/reset.css';

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
