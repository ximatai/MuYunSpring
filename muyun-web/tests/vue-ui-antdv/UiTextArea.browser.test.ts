import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import { expect, it } from 'vitest';
import { commands } from 'vitest/browser';
import UiTextArea from '@/vue-ui-antdv/components/UiTextArea.vue';
import UiTree from '@/vue-ui-antdv/components/UiTree.vue';
import 'ant-design-vue/dist/reset.css';

it('shows a caret marker and drops a typed field payload at the textarea insertion point', async () => {
  const drops = ref<Array<{ fieldName: string; start: number }>>([]);
  const Fixture = defineComponent({
    setup() {
      return () =>
        h('div', { id: 'formula-textarea-drag-fixture', style: 'width: 520px; padding: 24px' }, [
          h(UiTree, {
            nodes: [{ key: 'quantity', title: '数量' }],
            draggable: true,
            dragPayloadType: 'formula-field',
            dragPayloadOf: () => ({
              kind: 'formula-field',
              moduleAlias: 'education.exam',
              fieldName: 'quantity',
            }),
            canDrag: () => true,
          }),
          h(UiTextArea, {
            value: 'AA BB',
            rows: 3,
            acceptDrop: (source) => source.payloadType === 'formula-field',
            onDrop: (event) => {
              const payload = event.source.payload as { fieldName: string };
              drops.value.push({ fieldName: payload.fieldName, start: event.selection.start });
            },
          }),
        ]);
    },
  });
  const wrapper = mount(Fixture, { attachTo: document.body });
  try {
    await nextTick();
    await commands.treeGesture(
      '#formula-textarea-drag-fixture [data-ui-tree-key="quantity"]',
      '#formula-textarea-drag-fixture textarea',
      0.74,
      'hold',
      0.05,
    );
    await expect.poll(() => wrapper.find('.ui-text-area__drop-caret').exists()).toBe(true);
    await commands.treeRelease();
    expect(drops.value).toHaveLength(1);
    expect(drops.value[0]).toMatchObject({ fieldName: 'quantity' });
    expect(drops.value[0]!.start).toBeGreaterThan(0);
  } finally {
    await commands.treeRelease();
    wrapper.unmount();
  }
});

it('resolves a drop against the visible soft-wrapped and scrolled textarea line', async () => {
  const selections = ref<number[]>([]);
  const droppedValue = ref('');
  const value = '一二三四五六七八九十'.repeat(18);
  const Fixture = defineComponent({
    setup() {
      return () =>
        h('div', { id: 'formula-textarea-wrap-fixture', style: 'width: 240px; padding: 24px' }, [
          h(UiTree, {
            nodes: [{ key: 'source', title: '字段' }],
            draggable: true,
            dragPayloadType: 'formula-field',
            dragPayloadOf: () => ({
              kind: 'formula-field',
              moduleAlias: 'education.exam',
              fieldName: 'quantity',
            }),
            canDrag: () => true,
          }),
          h(UiTextArea, {
            value,
            rows: 2,
            acceptDrop: () => true,
            onDrop: (event) => {
              selections.value.push(event.selection.start);
              droppedValue.value = `${value.slice(0, event.selection.start)}{quantity}${value.slice(event.selection.start)}`;
            },
          }),
        ]);
    },
  });
  const wrapper = mount(Fixture, { attachTo: document.body });
  try {
    await nextTick();
    const textarea = wrapper.get('textarea').element as HTMLTextAreaElement;
    textarea.scrollTop = Math.max(1, textarea.scrollHeight);
    textarea.dispatchEvent(new Event('scroll'));
    // Chromium applies a textarea scroll at the next paint. Wait for that layout before the
    // gesture so the assertion exercises the visible wrapped line under full-suite load too.
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
    const initialScrollTop = textarea.scrollTop;
    expect(initialScrollTop).toBeGreaterThan(0);
    await commands.treeGesture(
      '#formula-textarea-wrap-fixture [data-ui-tree-key="source"]',
      '#formula-textarea-wrap-fixture textarea',
      0.9,
      'hold',
      0.05,
    );
    await expect.poll(() => wrapper.find('.ui-text-area__drop-caret').exists()).toBe(true);
    const marker = wrapper.get('.ui-text-area__drop-caret').element as HTMLElement;
    const markerTop = Number.parseFloat(marker.style.top);
    expect(markerTop).toBeGreaterThanOrEqual(0);
    expect(markerTop).toBeLessThan(textarea.clientHeight);
    await commands.treeRelease();
    expect(selections.value).toHaveLength(1);
    // The target is a visible wrapped line after a non-zero scroll offset; this guards against
    // treating the whole string as a single canvas line and always inserting at the origin.
    expect(selections.value[0]).toBeGreaterThan(value.length / 2);
    expect(droppedValue.value).toBe(
      `${value.slice(0, selections.value[0])}{quantity}${value.slice(selections.value[0])}`,
    );
  } finally {
    await commands.treeRelease();
    wrapper.unmount();
  }
});
