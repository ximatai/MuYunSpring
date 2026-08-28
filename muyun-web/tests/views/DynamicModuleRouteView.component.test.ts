import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import { expect, it, vi } from 'vitest';
import { providePageDescriptor, providePageRoute, type PageRoute } from '@/app/pageRouteContext';
import DynamicModuleRouteView from '@/views/DynamicModuleRouteView.vue';

const moduleHostRuntime = vi.hoisted(() => ({ instances: 0 }));

vi.mock('@muyun/dynamic-page-runtime', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    // eslint-disable-next-line vue/one-component-per-file -- local module-runtime stub.
    ModulePageHost: defineComponent({
      name: 'ModulePageHost',
      props: { descriptor: { type: Object, required: true } },
      setup(props) {
        const instance = ++moduleHostRuntime.instances;
        return () =>
          h(
            'p',
            { 'data-testid': 'module-runtime' },
            `${instance}:${(props.descriptor as { target: { moduleAlias: string } }).target.moduleAlias}`,
          );
      },
    }),
  };
});

it('rebuilds the module runtime instead of passing a cross-module descriptor into it', async () => {
  moduleHostRuntime.instances = 0;
  const route = ref<PageRoute>({
    path: '/app/iam/user',
    meta: { moduleAlias: 'iam.user', title: '用户管理' },
    query: {},
    params: {},
    matched: [{ path: '/app/iam/user' }],
  });
  // eslint-disable-next-line vue/one-component-per-file -- local route-context harness.
  const Harness = defineComponent({
    setup() {
      providePageRoute(() => route.value);
      providePageDescriptor(() => undefined);
      return () => h(DynamicModuleRouteView);
    },
  });
  const wrapper = mount(Harness);
  expect(wrapper.get('[data-testid="module-runtime"]').text()).toBe('1:iam.user');

  route.value = {
    path: '/app/iam/employee',
    meta: { moduleAlias: 'iam.employee', title: '职员管理' },
    query: {},
    params: {},
    matched: [{ path: '/app/iam/employee' }],
  };
  await nextTick();

  expect(wrapper.get('[data-testid="module-runtime"]').text()).toBe('2:iam.employee');
});
