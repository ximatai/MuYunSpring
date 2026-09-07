import { defineComponent, h, ref } from 'vue';
import { mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { commands } from 'vitest/browser';
import { usePageCompositionActionPreviewDrag } from '@/views/usePageCompositionActionPreviewDrag';

it('accepts a held entry over the same action in another region', async () => {
  const dropped = vi.fn();
  const Bar = defineComponent({
    props: { anchor: { type: String, required: true } },
    setup(props) {
      const root = ref<HTMLElement>();
      const actions = ref([{ actionCode: 'create', title: '新建' }]);
      const drag = usePageCompositionActionPreviewDrag(
        root,
        props.anchor as 'page' | 'form',
        actions,
        ref(true),
        () => true,
        dropped,
      );
      return () =>
        h(
          'div',
          {
            ref: root,
            id: `action-${props.anchor}`,
            style: 'padding:30px;margin:20px;min-height:100px',
          },
          drag.stagedActionCodes.value.map((code) =>
            h(
              'span',
              {
                'data-page-action-key': code,
                style: 'display:inline-block;padding:20px;border:1px solid gray',
              },
              [h('span', drag.dragHandleProps(code, code), code)],
            ),
          ),
        );
    },
  });
  const Host = defineComponent({
    render: () => h('div', [h(Bar, { anchor: 'page' }), h(Bar, { anchor: 'form' })]),
  });
  const wrapper = mount(Host, { attachTo: document.body });
  try {
    await commands.treeGesture(
      '#action-page [role=button]',
      '#action-form [data-page-action-key]',
      0.5,
      'hold',
    );
    expect(dropped).not.toHaveBeenCalled();
    await commands.treeRelease();
    await expect.poll(() => dropped.mock.calls.length).toBe(1);
    expect(dropped).toHaveBeenCalledWith(
      { actionCode: 'create', sourceAnchor: 'page' },
      { anchor: 'form', index: 0 },
    );
  } finally {
    await commands.treeRelease();
    wrapper.unmount();
  }
});
