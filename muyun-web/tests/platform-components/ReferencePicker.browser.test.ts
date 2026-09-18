import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick } from 'vue';
import { expect, it } from 'vitest';
import ReferencePicker from '@/platform-components/ReferencePicker.vue';
import type { ReferencePickerProvider } from '@/platform-components/referencePickerModel';

const provider: ReferencePickerProvider = {
  identity: { targetModuleAlias: 'demo.record', source: { kind: 'sourceField', id: 'ownerId' } },
  searchPage: async () => ({ records: [], total: 0 }),
  resolve: async () => [],
};

it('fills the width allocated by its form layout', async () => {
  const Fixture = defineComponent({
    setup() {
      return () => h('div', { style: 'width: 640px' }, [h(ReferencePicker, { provider })]);
    },
  });
  const wrapper = mount(Fixture, { attachTo: document.body });
  try {
    await nextTick();
    const picker = wrapper.get('.reference-picker').element;
    const input = wrapper.get('.object-picker-input-control').element;

    expect(Math.round(picker.getBoundingClientRect().width)).toBe(640);
    expect(Math.round(input.getBoundingClientRect().width)).toBe(640);
  } finally {
    wrapper.unmount();
  }
});
