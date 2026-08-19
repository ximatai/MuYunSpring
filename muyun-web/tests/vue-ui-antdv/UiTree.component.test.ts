import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import UiTree from '@/vue-ui-antdv/components/UiTree.vue';

it('emits deselect when Ant Tree clears the selected keys after a second click', () => {
  const wrapper = mount(UiTree, {
    props: {
      nodes: [{ key: 'rule-1', title: '规则' }],
      selectedKey: 'rule-1',
    },
    global: {
      stubs: {
        ATree: { name: 'ATree', template: '<div />' },
        UiRecordExplorerItem: true,
      },
    },
  });

  wrapper.findComponent({ name: 'ATree' }).vm.$emit('select', []);

  expect(wrapper.emitted('deselect')).toEqual([[]]);
});
