import { flushPromises, mount } from '@vue/test-utils';
import { KeepAlive, defineComponent, nextTick, ref, watch } from 'vue';
import { RouterView, createMemoryHistory, createRouter, useRouter } from 'vue-router';
import { expect, it } from 'vitest';
import StaticRoutePageHost from '@/app/StaticRoutePageHost.vue';
import { usePageRoute } from '@/app/pageRouteContext';
import { pageCacheKey } from '@/platform-workbench/pageCacheKey';
import Workbench from '@/platform-workbench/Workbench.vue';
import type { WorkbenchStartupState } from '@/web-contracts';

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

// eslint-disable-next-line vue/one-component-per-file -- This harness mirrors App.vue's production workbench route boundary.
const RouteCacheHarness = defineComponent({
  components: { KeepAlive, RouterView, StaticRoutePageHost, Workbench },
  setup() {
    const router = useRouter();
    const startup = ref<WorkbenchStartupState>({
      session: { currentUser: { userId: 'user-1', system: false } },
      menus: [],
      tabs: [
        { key: 'page:tab-a', title: 'A', fullPath: '/page?InstanceKey=tab-a', closable: true },
        { key: 'page:tab-b', title: 'B', fullPath: '/page?InstanceKey=tab-b', closable: true },
      ],
      activeTabKey: 'page:tab-a',
    });
    const activeTabKey = ref('page:tab-a');
    const pageRefreshRevisions = ref<Record<string, number>>({});

    async function changeTab(key: string) {
      const tab = startup.value.tabs?.find((item) => item.key === key);
      if (!tab?.fullPath) return;
      activeTabKey.value = key;
      startup.value = { ...startup.value, activeTabKey: key };
      await router.push(tab.fullPath);
    }

    function pageRefreshRevisionFor(tabKey: string) {
      return pageRefreshRevisions.value[tabKey] ?? 0;
    }

    function refreshPage(tabKey: string) {
      pageRefreshRevisions.value = {
        ...pageRefreshRevisions.value,
        [tabKey]: pageRefreshRevisionFor(tabKey) + 1,
      };
    }

    return { activeTabKey, changeTab, pageCacheKey, pageRefreshRevisionFor, refreshPage, startup };
  },
  template: `
    <Workbench
      :startup="startup"
      :active-tab-key="activeTabKey"
      @change-tab="changeTab"
      @refresh-page="refreshPage"
    >
      <template #default>
        <RouterView v-slot="{ Component, route }">
          <KeepAlive>
            <StaticRoutePageHost
              :key="pageCacheKey(route, String(route.query.InstanceKey ?? 'default'))"
              :component="Component"
              :route="route"
              :refresh-revision="pageRefreshRevisionFor(activeTabKey)"
            />
          </KeepAlive>
        </RouterView>
      </template>
    </Workbench>
  `,
});

it('keeps tab drafts isolated and refreshes only the current page instance', async () => {
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
  await wrapper.findComponent(Workbench).vm.$emit('changeTab', 'page:tab-b');
  await flushPromises();
  await wrapper.get('[data-testid="draft"]').setValue('draft-b');
  await wrapper.findComponent(Workbench).vm.$emit('changeTab', 'page:tab-a');
  await flushPromises();

  expect(wrapper.get<HTMLInputElement>('[data-testid="draft"]').element.value).toBe('draft-a');

  await wrapper.get('[aria-label="刷新当前页"]').trigger('click');
  await flushPromises();

  expect(wrapper.get<HTMLInputElement>('[data-testid="draft"]').element.value).toBe('initial:tab-a');

  await wrapper.findComponent(Workbench).vm.$emit('changeTab', 'page:tab-b');
  await flushPromises();

  expect(wrapper.get<HTMLInputElement>('[data-testid="draft"]').element.value).toBe('draft-b');
  wrapper.unmount();
});
