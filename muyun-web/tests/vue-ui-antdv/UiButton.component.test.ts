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

  it('exposes a fixed hit-area variant for icon-only actions', () => {
    const wrapper = mount(UiButton, { props: { iconName: 'download', iconOnly: true, title: '下载' } });

    expect(wrapper.classes()).toContain('ui-button--icon-only');
    expect(wrapper.attributes('title')).toBe('下载');
  });

  it('supports a compact square hit area for dense icon-only actions', () => {
    const wrapper = mount(UiButton, { props: { iconName: 'download', iconOnly: true, size: 'small' } });

    expect(wrapper.classes()).toContain('ui-button--icon-only-compact');
  });
});
