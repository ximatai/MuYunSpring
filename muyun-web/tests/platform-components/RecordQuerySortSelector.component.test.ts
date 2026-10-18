import { shallowMount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import RecordQuerySortSelector from '@/platform-components/RecordQuerySortSelector.vue';

it('offers only declared sort fields and keeps default order an explicit choice', async () => {
  const wrapper = shallowMount(RecordQuerySortSelector, {
    props: { fields: [{ name: 'title', title: '名称' }] },
  });
  const dropdown = wrapper.findComponent({ name: 'UiDropdown' });
  expect(dropdown.props('items').map((item: { key: string }) => item.key)).toEqual([
    'default',
    'title:asc',
    'title:desc',
  ]);
  dropdown.vm.$emit('select', 'title:desc');
  expect(wrapper.emitted('change')?.at(-1)).toEqual([[{ field: 'title', desc: true }]]);
  dropdown.vm.$emit('select', 'private:asc');
  expect(wrapper.emitted('change')).toHaveLength(1);
  dropdown.vm.$emit('select', 'default');
  expect(wrapper.emitted('change')?.at(-1)).toEqual([undefined]);
  await wrapper.setProps({ disabled: true });
  dropdown.vm.$emit('select', 'title:asc');
  expect(wrapper.emitted('change')).toHaveLength(2);
});

it('shows the active sort field and direction and restores the neutral label for the default', async () => {
  const wrapper = shallowMount(RecordQuerySortSelector, {
    props: {
      fields: [{ name: 'title', title: '名称' }],
      sorts: [{ field: 'title', desc: true }],
    },
    global: {
      stubs: {
        UiDropdown: { template: '<div><slot /></div>' },
        UiButton: { template: '<button><slot /></button>' },
      },
    },
  });
  expect(wrapper.text()).toContain('名称降序');
  await wrapper.setProps({ sorts: [{ field: 'title', desc: false }] });
  expect(wrapper.text()).toContain('名称升序');
  await wrapper.setProps({ sorts: undefined });
  expect(wrapper.text()).toContain('排序方式');
});
