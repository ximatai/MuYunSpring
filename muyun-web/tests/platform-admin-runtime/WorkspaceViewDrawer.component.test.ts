import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import WorkspaceViewDrawer from '../../src/platform-admin-runtime/WorkspaceViewDrawer.vue';

describe('WorkspaceViewDrawer', () => {
  it('forwards the workspace leave policy to the standard detail drawer', () => {
    const beforeClose = vi.fn(() => true);
    const wrapper = mount(WorkspaceViewDrawer, {
      props: {
        open: true,
        title: '角色授权',
        container: null,
        dismissal: 'guarded',
        beforeClose,
      },
      global: {
        stubs: {
          RecordDetailDrawer: {
            name: 'RecordDetailDrawer',
            props: ['width', 'dismissal', 'beforeClose'],
            template: '<section><slot /></section>',
          },
        },
      },
    });

    const drawer = wrapper.findComponent({ name: 'RecordDetailDrawer' });
    expect(drawer.props('width')).toBe('standard');
    expect(drawer.props('dismissal')).toBe('guarded');
    expect(drawer.props('beforeClose')).toBe(beforeClose);
    wrapper.unmount();
  });
});
