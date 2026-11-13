import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import StaticManagementLayout from '@/platform-components/StaticManagementLayout.vue';
import '@/styles.css';

it('folds an opted-in tenant navigator to keep detail operations inside a narrow workspace', async () => {
  await page.viewport(980, 900);
  const wrapper = mount(StaticManagementLayout, {
    attachTo: document.body,
    props: {
      explorerTitle: '策略',
      refreshTitle: '刷新',
      detailTitle: '密码安全',
      mode: 'edit',
      navigatorCount: 1,
      navigatorOptions: [{ title: '租户', collapsible: true, hasSelection: true }],
    },
    slots: {
      navigator: '<div>租户列表</div>',
      explorer: '<div>当前策略</div>',
      default: '<input aria-label="阈值" />',
      'detail-operation': '<button>保存配置</button>',
    },
  });
  try {
    await expect.poll(() => wrapper.findAll('.management-explorer-column--collapsed').length).toBe(1);
    const detail = wrapper.get('.record-detail-panel-region').element.getBoundingClientRect();
    const save = wrapper.get('.record-detail-layout-operation button').element.getBoundingClientRect();
    expect(detail.right).toBeLessThanOrEqual(980);
    expect(save.right).toBeLessThanOrEqual(980);
    expect(save.width).toBeGreaterThan(0);
    expect(wrapper.get('.management-explorer-column-tab-title').text()).toBe('租户');
  } finally {
    wrapper.unmount();
  }
});
