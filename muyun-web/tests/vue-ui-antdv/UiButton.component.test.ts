import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import UiButton from '@/vue-ui-antdv/components/UiButton.vue';

describe('UiButton', () => {
  it('uses the semantic foreground for primary and dangerous primary buttons', () => {
    const primary = mount(UiButton, { props: { type: 'primary' } });
    const danger = mount(UiButton, { props: { type: 'primary', danger: true } });

    expect(primary.get('button').classes()).toContain('ui-button--theme-solid');
    expect(danger.get('button').classes()).toContain('ui-button--danger-solid');
  });
});
