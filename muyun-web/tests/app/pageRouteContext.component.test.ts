import { flushPromises, mount } from '@vue/test-utils';
import { KeepAlive, computed, defineComponent, h, nextTick, ref, watch, type Component } from 'vue';
import { RouterView, createMemoryHistory, createRouter, useRouter } from 'vue-router';
import { expect, it } from 'vitest';
import StaticRoutePageHost from '@/app/StaticRoutePageHost.vue';
import { usePageRoute } from '@/app/pageRouteContext';
import { pageCacheKey } from '@/platform-workbench/pageCacheKey';
import Workbench from '@/platform-workbench/Workbench.vue';
import type { WorkbenchStartupState } from '@/web-contracts';
import { workbenchRouteCommitFor } from '@/app/workbenchRouteSync';

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
        // Workbench identity is allowed to differ even when the public address
        // is identical (for example two menu entries targeting one module).
        { key: 'page:tab-b', title: 'B', fullPath: '/page?InstanceKey=tab-b', closable: true },
      ],
      activeTabKey: 'page:tab-a',
    });
    const activeTabKey = ref('page:tab-a');
    const renderedTabKey = ref('page:tab-a');
    const pageRefreshRevisions = ref<Record<string, number>>({});
    const pageCacheGenerations = ref<Record<string, number>>({});
    const pageCacheMax = computed(() => Math.max(startup.value.tabs?.length ?? 0, 1));
    const pageCacheHosts = new Map<string, Component>();
    const pageCacheHostNames = new Map<string, string>();
    const cachedTabPageHostNames = computed(() =>
      (startup.value.tabs ?? []).map((tab) => pageCacheHostNameFor(tab.key)),
    );
    let navigationRevision = 0;
    let latestNavigation: { url: string; revision: number } | undefined;
    let pendingNavigation: { url: string; revision: number } | undefined;

    watch(
      () => router.currentRoute.value.fullPath,
      (url) => {
        const commit = workbenchRouteCommitFor(url, pendingNavigation, latestNavigation);
        if (commit === 'reconcile' && latestNavigation) {
          pendingNavigation = latestNavigation;
          void router.replace(latestNavigation.url).finally(() => {
            if (pendingNavigation === latestNavigation) pendingNavigation = undefined;
          });
          return;
        }
        if (commit === 'commit') pendingNavigation = undefined;
        commitRenderedTab(activeTabKey.value);
      },
    );

    function commitRenderedTab(key: string) {
      renderedTabKey.value = key;
    }

    function changeTab(key: string) {
      const tab = startup.value.tabs?.find((item) => item.key === key);
      if (!tab?.fullPath) return;
      activeTabKey.value = key;
      startup.value = { ...startup.value, activeTabKey: key };
      const intent = { url: tab.fullPath, revision: ++navigationRevision };
      latestNavigation = intent;
      if (tab.fullPath === router.currentRoute.value.fullPath) {
        commitRenderedTab(key);
        return;
      }
      pendingNavigation = intent;
      void router.push(tab.fullPath).finally(() => {
        if (pendingNavigation === intent) pendingNavigation = undefined;
      });
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

    function pageRuntimeCacheKey(route: Parameters<typeof pageCacheKey>[0], tabKey: string) {
      return `${pageCacheKey(route, tabKey)}:${pageCacheGenerations.value[tabKey] ?? 0}`;
    }

    function pageCacheHostFor(tabKey: string): Component {
      const existing = pageCacheHosts.get(tabKey);
      if (existing) return existing;
      const name = `TestWorkbenchTabPageHost${pageCacheHosts.size + 1}`;
      // eslint-disable-next-line vue/one-component-per-file -- tab-scoped cache host test double.
      const host = defineComponent({
        name,
        setup(_, { attrs }) {
          return () => h(StaticRoutePageHost as Component, attrs);
        },
      });
      pageCacheHosts.set(tabKey, host);
      pageCacheHostNames.set(tabKey, name);
      return host;
    }

    function pageCacheHostNameFor(tabKey: string): string {
      pageCacheHostFor(tabKey);
      return pageCacheHostNames.get(tabKey)!;
    }

    async function closeTab(key: string) {
      pageCacheGenerations.value = {
        ...pageCacheGenerations.value,
        [key]: (pageCacheGenerations.value[key] ?? 0) + 1,
      };
      const tabs = (startup.value.tabs ?? []).filter((tab) => tab.key !== key);
      const nextActiveKey = key === activeTabKey.value ? (tabs[0]?.key ?? '') : activeTabKey.value;
      activeTabKey.value = nextActiveKey;
      startup.value = { ...startup.value, tabs, activeTabKey: nextActiveKey };
      const nextTab = tabs.find((tab) => tab.key === nextActiveKey);
      if (nextTab?.fullPath) await router.push(nextTab.fullPath);
    }

    async function reopenTab(key: string) {
      const tab = {
        key,
        title: key,
        fullPath: `/page?InstanceKey=${key.replace('page:', '')}`,
        closable: true,
      };
      startup.value = { ...startup.value, tabs: [...(startup.value.tabs ?? []), tab], activeTabKey: key };
      activeTabKey.value = key;
      if (tab.fullPath === router.currentRoute.value.fullPath) {
        commitRenderedTab(key);
        return;
      }
      await router.push(tab.fullPath);
    }

    return {
      activeTabKey,
      changeTab,
      closeTab,
      pageCacheKey,
      cachedTabPageHostNames,
      pageCacheHostFor,
      pageCacheMax,
      pageRefreshRevisionFor,
      pageRuntimeCacheKey,
      refreshPage,
      renderedTabKey,
      reopenTab,
      startup,
    };
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
          <KeepAlive :include="cachedTabPageHostNames" :max="pageCacheMax">
            <component
              :is="pageCacheHostFor(renderedTabKey)"
              :key="pageRuntimeCacheKey(route, renderedTabKey)"
              :component="Component"
              :route="route"
              :refresh-revision="pageRefreshRevisionFor(renderedTabKey)"
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

it('discards only the closed tab page state so reopening it starts fresh', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/page', component: StatefulPage, meta: { cacheable: true } }],
  });
  await router.push('/page?InstanceKey=tab-a');
  await router.isReady();
  const wrapper = mount(RouteCacheHarness, { global: { plugins: [router] } });
  await nextTick();

  await wrapper.get('[data-testid="draft"]').setValue('draft-a');
  await wrapper.vm.changeTab('page:tab-b');
  await flushPromises();
  await wrapper.get('[data-testid="draft"]').setValue('draft-b');

  await wrapper.vm.closeTab('page:tab-a');
  await flushPromises();
  expect(wrapper.get<HTMLInputElement>('[data-testid="draft"]').element.value).toBe('draft-b');

  await wrapper.vm.reopenTab('page:tab-a');
  await flushPromises();
  expect(wrapper.get<HTMLInputElement>('[data-testid="draft"]').element.value).toBe('initial:tab-a');
  wrapper.unmount();
});

it('prunes a closed non-current tab without evicting the still-open sibling draft', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/page', component: StatefulPage, meta: { cacheable: true } }],
  });
  await router.push('/page?InstanceKey=tab-a');
  await router.isReady();
  const wrapper = mount(RouteCacheHarness, { global: { plugins: [router] } });
  await nextTick();
  await wrapper.get('[data-testid="draft"]').setValue('draft-a');
  wrapper.vm.changeTab('page:tab-b');
  await flushPromises();
  await wrapper.get('[data-testid="draft"]').setValue('draft-b');

  // A is inactive; closing it must prune its cache entry before another page
  // consumes a KeepAlive slot.
  await wrapper.vm.closeTab('page:tab-a');
  await flushPromises();
  await wrapper.vm.reopenTab('page:tab-c');
  await flushPromises();
  await wrapper.get('[data-testid="draft"]').setValue('draft-c');
  wrapper.vm.changeTab('page:tab-b');
  await flushPromises();
  expect(wrapper.get<HTMLInputElement>('[data-testid="draft"]').element.value).toBe('draft-b');

  await wrapper.vm.reopenTab('page:tab-a');
  await flushPromises();
  expect(wrapper.get<HTMLInputElement>('[data-testid="draft"]').element.value).toBe('initial:tab-a');
  wrapper.unmount();
});

it('keeps the latest tab runtime while an earlier route navigation commits late', async () => {
  let releaseDelayedNavigation: (() => void) | undefined;
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/page', component: StatefulPage, meta: { cacheable: true } }],
  });
  router.beforeEach((to) => {
    if (to.fullPath !== '/page?InstanceKey=tab-b') return true;
    return new Promise<boolean>((resolve) => {
      releaseDelayedNavigation = () => resolve(true);
    });
  });
  await router.push('/page?InstanceKey=tab-a');
  await router.isReady();
  const wrapper = mount(RouteCacheHarness, { global: { plugins: [router] } });
  await nextTick();
  await wrapper.get('[data-testid="draft"]').setValue('draft-a');

  wrapper.vm.changeTab('page:tab-b');
  await nextTick();
  wrapper.vm.changeTab('page:tab-a');
  releaseDelayedNavigation?.();
  await flushPromises();

  expect(router.currentRoute.value.fullPath).toBe('/page?InstanceKey=tab-a');
  expect(wrapper.vm.renderedTabKey).toBe('page:tab-a');
  expect(wrapper.get<HTMLInputElement>('[data-testid="draft"]').element.value).toBe('draft-a');
  wrapper.unmount();
});
