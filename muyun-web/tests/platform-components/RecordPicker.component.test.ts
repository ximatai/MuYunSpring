import { flushPromises, shallowMount } from '@vue/test-utils';
import { assert, it, vi } from 'vitest';
import RecordPicker from '@/platform-components/RecordPicker.vue';

it('uses the list query transport for an explicitly LIST reference even when tree capability exists', async () => {
  const query = vi.fn().mockResolvedValue({ records: [{ id: 'department-1', title: '研发部' }] });
  const tree = vi.fn().mockResolvedValue({ records: [] });
  const wrapper = shallowMount(RecordPicker, {
    props: {
      mode: 'list',
      context: {
        runtime: { ready: Promise.resolve() },
        abilities: { tryTree: () => ({ tree }) },
        crud: { query },
      } as never,
    },
  });

  await flushPromises();

  assert.equal(query.mock.calls.length, 1);
  assert.equal(tree.mock.calls.length, 0);
  assert.equal(wrapper.find('ui-select-stub').exists(), true);
});
