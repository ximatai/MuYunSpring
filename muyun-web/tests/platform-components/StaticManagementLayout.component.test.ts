import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import StaticManagementLayout from '@/platform-components/StaticManagementLayout.vue';

it('owns the fixed detail operation region and locks navigation during editing or requests', async () => {
  const wrapper = mount(StaticManagementLayout, {
    props: { explorerTitle: '流程列表', refreshTitle: '刷新流程', detailTitle: '流程详情', mode: 'view' },
    slots: {
      explorer: '<button>选择流程</button>',
      default: '<input aria-label="流程名称" />',
      'detail-operation': '<button>保存配置</button>',
    },
  });
  const column = () => wrapper.get('.management-explorer-column');
  expect(wrapper.get('.record-detail-layout-operation').text()).toContain('保存配置');
  expect(column().attributes('inert')).toBeUndefined();
  await wrapper.setProps({ busy: true });
  expect(column().attributes('inert')).toBeDefined();
  expect(wrapper.get('input[aria-label="流程名称"]').element.closest('[inert]')).toBeNull();
  await wrapper.setProps({ busy: false, mode: 'create' });
  expect(column().attributes('inert')).toBeDefined();
  await wrapper.setProps({ mode: 'view' });
  expect(column().attributes('inert')).toBeUndefined();
  wrapper.unmount();
});

it('does not reserve an operation region when the caller has no detail operations', () => {
  const wrapper = mount(StaticManagementLayout, {
    props: { explorerTitle: '应用列表', refreshTitle: '刷新应用', detailTitle: '记录详情', mode: 'view' },
  });
  expect(wrapper.find('.record-detail-layout-operation').exists()).toBe(false);
  wrapper.unmount();
});
