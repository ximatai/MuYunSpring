import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import UiActionButton from '@/vue-ui-antdv/components/UiActionButton.vue';
import UiButton from '@/vue-ui-antdv/components/UiButton.vue';

describe('UiButton', () => {
  it('names icon-only actions without replacing a text action with its tooltip', () => {
    const close = mount(UiActionButton, { props: { iconName: 'close', title: '关闭' } });
    const action = mount(UiActionButton, {
      props: { title: '正在校验操作可用性', disabled: true },
      slots: { default: '删除' },
    });
    expect(close.get('button').attributes('aria-label')).toBe('关闭');
    expect(action.get('button').attributes('aria-label')).toBeUndefined();
    expect(action.get('button').text().replace(/\s/g, '')).toBe('删除');
  });

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
