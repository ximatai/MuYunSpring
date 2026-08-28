import { mount } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { expect, it } from 'vitest';
import StaticRoutePageHost from '@/app/StaticRoutePageHost.vue';
import { ModuleContextProvider } from '@/web-core/module/moduleContext.ts';

it('provides static route module context before rendering the route component', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/config/applications',
        component: { template: '<div />' },
        meta: { entryType: 'route', moduleAlias: 'platform.application', layout: 'workspace' },
      },
    ],
  });
  await router.push('/config/applications');
  await router.isReady();
  // eslint-disable-next-line vue/one-component-per-file -- local route-host contract component.
  const page = defineComponent({ template: '<p data-testid="static-page">page</p>' });
  const wrapper = mount(StaticRoutePageHost, {
    props: { component: page, route: router.currentRoute.value },
  });

  expect(wrapper.findComponent(ModuleContextProvider).props('moduleAlias')).toBe('platform.application');
  expect(wrapper.get('[data-testid="static-page"]').text()).toBe('page');
});

it('rebuilds a route runtime when its route identity changes', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/iam/users', component: { template: '<div />' }, meta: { moduleAlias: 'iam.user' } },
      { path: '/iam/employees', component: { template: '<div />' }, meta: { moduleAlias: 'iam.employee' } },
    ],
  });
  await router.push('/iam/users');
  await router.isReady();
  let instances = 0;
  // eslint-disable-next-line vue/one-component-per-file -- local route-runtime contract component.
  const page = defineComponent({
    setup() {
      return { instance: ++instances };
    },
    template: '<p data-testid="page-instance">{{ instance }}</p>',
  });
  const wrapper = mount(StaticRoutePageHost, {
    props: { component: page, route: router.currentRoute.value },
  });
  expect(wrapper.get('[data-testid="page-instance"]').text()).toBe('1');

  await router.push('/iam/employees');
  await wrapper.setProps({ route: router.currentRoute.value });
  await nextTick();

  expect(wrapper.get('[data-testid="page-instance"]').text()).toBe('2');
});
