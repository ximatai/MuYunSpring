import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import UiIcon from '@/vue-ui-antdv/components/UiIcon.vue';

describe('UiIcon', () => {
  it('renders the Lucide pin and unpin symbols through platform semantics', () => {
    const pinned = mount(UiIcon, { props: { name: 'pin' } });
    const unpinned = mount(UiIcon, { props: { name: 'pin-off' } });

    expect(pinned.get('svg').attributes('stroke-width')).toBe('1.8');
    expect(unpinned.get('svg').attributes('stroke-width')).toBe('1.8');
    expect(unpinned.get('svg').html()).not.toBe(pinned.get('svg').html());
  });

  it('rotates the official swap symbol for vertical drag-order actions', () => {
    const verticalSort = mount(UiIcon, { props: { name: 'swap-vertical' } });

    expect(verticalSort.get('svg').attributes('style')).toContain('rotate(90deg)');
  });
});
