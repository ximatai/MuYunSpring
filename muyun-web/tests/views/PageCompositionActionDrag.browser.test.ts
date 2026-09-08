import { defineComponent, h, ref, TransitionGroup } from 'vue';
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

it.each(['page', 'detail', 'form'] as const)(
  'keeps %s preview order stable while holding and returning across fixed action slots',
  async (anchor) => {
    const actions = ref(['edit', 'delete', 'enable', 'disable'].map((actionCode) => ({ actionCode })));
    const dropped = vi.fn();
    const Bar = defineComponent({
      setup() {
        const root = ref<HTMLElement>();
        const drag = usePageCompositionActionPreviewDrag(
          root,
          anchor,
          actions,
          ref(true),
          () => true,
          dropped,
        );
        return () =>
          h('div', { ref: root, id: 'stable-actions', style: 'display:flex;width:400px;margin:40px' }, [
            h(TransitionGroup, { name: 'test-action' }, () =>
              drag.stagedActionCodes.value.map((code) =>
                h(
                  'span',
                  {
                    key: code,
                    'data-page-action-key': code,
                    style:
                      'display:inline-flex;box-sizing:border-box;width:100px;height:50px;border:1px solid gray',
                  },
                  [h('span', drag.dragHandleProps(code, code), code)],
                ),
              ),
            ),
          ]);
      },
    });
    const style = document.createElement('style');
    style.textContent = '.test-action-move { transition: transform 220ms linear; }';
    document.head.append(style);
    const wrapper = mount(Bar, { attachTo: document.body });
    const order = () =>
      wrapper.findAll('[data-page-action-key]').map((node) => node.attributes('data-page-action-key'));
    try {
      await commands.treeGesture(
        '#stable-actions [data-page-action-key=edit] [role=button]',
        '#stable-actions',
        0.5,
        'hold',
        0.375,
      );
      for (const index of [1, 2, 3, 3, 2, 1, 0]) {
        await commands.treeMove('#stable-actions', 0.5, (index + 0.5) / 4);
        const expected = ['delete', 'enable', 'disable'];
        expected.splice(index, 0, 'edit');
        await expect.poll(order).toEqual(expected);
        // Repeated tiny moves after the layout transition must not alternate the target.
        await new Promise((resolve) => setTimeout(resolve, 260));
        await commands.treeMove('#stable-actions', 0.5, (index + 0.51) / 4);
        expect(order()).toEqual(expected);
      }
      expect(dropped).not.toHaveBeenCalled();
      await commands.treeRelease();
      expect(dropped).toHaveBeenCalledWith(
        { actionCode: 'edit', sourceAnchor: anchor },
        { anchor, index: 0 },
      );
    } finally {
      await commands.treeRelease();
      wrapper.unmount();
      style.remove();
    }
  },
);
