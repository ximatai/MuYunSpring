import { readFileSync } from 'node:fs';
import * as Vue from 'vue';
import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';

it.each([
  ['workspace', 'tab'],
  ['flow', 'tab'],
  ['workspace', 'drawer'],
  ['flow', 'drawer'],
] as const)('honors %s layout in the installed SDK %s host', async (layout, presentation) => {
  const bundle = process.env.MUYUN_CONSUMER_CONTRACT_BUNDLE;
  if (!bundle) throw new Error('Run through npm run verify:consumer.');
  const sdk = new Function('Vue', `${readFileSync(bundle, 'utf8')}\nreturn MuyunConsumerContract;`)(Vue);
  const definition = {
    type: 'consumer.layout',
    route: '/consumer/layout',
    moduleAlias: 'consumer.records',
    presentations: ['tab', 'drawer'],
    layout,
    titleOf: () => '消费者布局',
    parse: () => ({}),
    component: Vue.defineComponent({
      setup() {
        return () => Vue.h(sdk.RecordDetailPanel, { title: '布局' }, () => Vue.h('input'));
      },
    }),
  };
  sdk.configureWorkspaceViewContributions('consumer-layout', [definition]);
  const descriptor = sdk.createWorkspaceViewDescriptor(definition, {}, presentation);
  delete descriptor.layout;
  const wrapper = mount(sdk.PlatformAdminOutlet, { props: { descriptor } });
  try {
    expect(wrapper.find('.record-detail-layout--scrollable').exists()).toBe(layout === 'workspace');
    expect(wrapper.find('.record-detail-layout-content').exists()).toBe(layout === 'workspace');
    await wrapper.get('input').setValue('保留草稿');
    await wrapper.setProps({ descriptor: { ...descriptor, title: '新标题' } });
    expect(wrapper.get('input').element.value).toBe('保留草稿');
  } finally {
    wrapper.unmount();
    sdk.configureWorkspaceViewContributions('consumer-layout', []);
  }
});
