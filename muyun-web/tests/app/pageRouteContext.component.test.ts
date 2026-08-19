import { flushPromises, mount } from '@vue/test-utils';
import { KeepAlive, defineComponent, nextTick, ref, watch } from 'vue';
import { RouterView, createMemoryHistory, createRouter } from 'vue-router';
import { expect, it } from 'vitest';
import StaticRoutePageHost from '@/app/StaticRoutePageHost.vue';
import { usePageRoute } from '@/app/pageRouteContext';
import { pageCacheKey } from '@/platform-workbench/pageCacheKey';

// eslint-disable-next-line vue/one-component-per-file -- This page exists only to verify cache behavior.
const StatefulPage = defineComponent({
  name: 'StatefulPage',
  setup() {
    const route = usePageRoute();
    const draft = ref('');
    watch(
      () => route.value.query.InstanceKey,
      (instanceKey) => {
        draft.value = `initial:${String(instanceKey)}`;
      },
      { immediate: true },
    );
    return { draft };
  },
  template: '<input data-testid="draft" v-model="draft" />',
});

// eslint-disable-next-line vue/one-component-per-file -- This harness mirrors App.vue's route cache boundary.
const RouteCacheHarness = defineComponent({
  components: { KeepAlive, RouterView, StaticRoutePageHost },
  setup() {
    return { pageCacheKey };
  },
  template: `
    <RouterView v-slot="{ Component, route }">
      <KeepAlive>
        <StaticRoutePageHost :key="pageCacheKey(route)" :component="Component" :route="route" />
      </KeepAlive>
    </RouterView>
  `,
});

it('does not let another tab route reset the cached page draft', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/page', component: StatefulPage, meta: { cacheable: true } }],
  });
  await router.push('/page?InstanceKey=tab-a');
  await router.isReady();
  const wrapper = mount(RouteCacheHarness, { global: { plugins: [router] } });
  await nextTick();

  const draft = wrapper.get('[data-testid="draft"]');
  await draft.setValue('draft-a');
  await router.push('/page?InstanceKey=tab-b');
  await flushPromises();
  await router.push('/page?InstanceKey=tab-a');
  await flushPromises();

  expect(wrapper.get<HTMLInputElement>('[data-testid="draft"]').element.value).toBe('draft-a');
  wrapper.unmount();
});
