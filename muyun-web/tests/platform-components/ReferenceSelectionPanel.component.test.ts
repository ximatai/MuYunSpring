import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import ReferenceSelectionPanel from '@/platform-components/ReferenceSelectionPanel.vue';

beforeEach(() => {
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener: vi.fn(),
    removeListener: vi.fn(),
  }));
});
afterEach(() => vi.unstubAllGlobals());

it('searches the complete selection independently of pagination and removes by ID', async () => {
  const candidates = Array.from({ length: 12 }, (_, index) => ({
    id: `user-${index + 1}`,
    title: '同名人员',
    subtitle: `部门${index + 1}`,
  }));
  const wrapper = mount(ReferenceSelectionPanel, { props: { candidates } });
  try {
    expect(wrapper.text()).toContain('已选 12 个记录');
    expect(wrapper.findAll('tbody tr[data-row-key]')).toHaveLength(8);
    await wrapper.get('input').setValue('部门12');
    await flushPromises();
    expect(wrapper.findAll('tbody tr[data-row-key]')).toHaveLength(1);
    await wrapper.get('button[aria-label="移除 同名人员 · 部门12"]').trigger('click');
    expect(wrapper.emitted('remove')).toEqual([['user-12']]);
    expect(wrapper.props('candidates')).toHaveLength(12);
    expect(wrapper.text()).toContain('已选 12 个记录');
  } finally {
    wrapper.unmount();
  }
});

it('keeps unresolved and unavailable selections and disambiguates identical display names', async () => {
  const wrapper = mount(ReferenceSelectionPanel, {
    props: {
      candidates: [
        { id: 'user-a', title: '同名' },
        { id: 'user-b', title: '同名' },
        { id: 'unknown', title: 'unknown' },
        { id: 'removed', title: '历史人员', unavailable: true },
      ],
    },
  });
  try {
    expect(wrapper.text()).toContain('user-a');
    expect(wrapper.text()).toContain('user-b');
    expect(wrapper.text()).toContain('unknown');
    expect(wrapper.text()).toContain('历史人员（不可用）');
    expect(wrapper.text()).not.toContain('unknown（不可用）');
    expect(wrapper.emitted('remove')).toBeUndefined();
    await wrapper.get('input').setValue('不存在');
    await flushPromises();
    expect(wrapper.text()).toContain('没有匹配的已选内容');
    expect(wrapper.props('candidates')).toHaveLength(4);
  } finally {
    wrapper.unmount();
  }
});

it('returns to an available page after removing the last row on the final page', async () => {
  const candidates = Array.from({ length: 9 }, (_, index) => ({ id: `id-${index}`, title: `Name ${index}` }));
  const wrapper = mount(ReferenceSelectionPanel, { props: { candidates } });
  try {
    const table = wrapper.findComponent({ name: 'UiDataTable' });
    (table.props('pagination') as { onChange(page: number): void }).onChange(2);
    await flushPromises();
    expect(wrapper.text()).toContain('Name 8');
    await wrapper.setProps({ candidates: candidates.slice(0, 8) });
    await flushPromises();
    expect(table.props('pagination')).toBe(false);
    expect(wrapper.text()).toContain('Name 0');
    expect(wrapper.findAll('tbody tr[data-row-key]')).toHaveLength(8);
  } finally {
    wrapper.unmount();
  }
});
