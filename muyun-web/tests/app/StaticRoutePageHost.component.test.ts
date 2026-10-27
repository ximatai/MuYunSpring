import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { expect, it, vi } from 'vitest';
import StaticRoutePageHost from '@/app/StaticRoutePageHost.vue';
import { ModuleContextProvider } from '@/web-core/module/moduleContext.ts';
import { usePageLayout } from '@/platform-components/pageLayoutContext.ts';
import { useWorkspaceViewUnsavedState } from '@muyun/web-core';
import { tabKeyOf, withPageInstanceKey } from '@/platform-workbench/menuNavigation';
import {
  confirmDiscardWorkspaceViewState,
  hasWorkspaceViewDiscardProtection,
} from '@/platform-workbench/workspaceViewDiscardGuard';
import type { BusinessRoutePageDescriptor } from '@/web-contracts';
import { workspaceViewUnsavedStateSources } from '@/platform-workbench/workspaceViewUnsavedState';
import { provideWorkbenchNavigation } from '@/platform-workbench/workbenchNavigation';
import { useModulePageNavigation } from '@/dynamic-page-runtime/modulePageNavigation';

const feedback = vi.hoisted(() => ({ confirm: vi.fn(), error: vi.fn() }));
vi.mock('@muyun/vue-ui-antdv', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/vue-ui-antdv')>()),
  confirmAction: feedback.confirm,
  showErrorMessage: feedback.error,
}));

it('provides static route module context before rendering the route component', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/config/applications',
        component: { template: '<div />' },
        meta: { entryType: 'route', moduleAlias: 'platform.application' },
      },
    ],
  });
  await router.push('/config/applications');
  await router.isReady();
  // eslint-disable-next-line vue/one-component-per-file -- local route-host contract component.
  const page = defineComponent({
    setup() {
      return { pageLayout: usePageLayout() };
    },
    template: '<p data-testid="static-page">{{ pageLayout }}</p>',
  });
  const wrapper = mount(StaticRoutePageHost, {
    props: {
      component: page,
      route: router.currentRoute.value,
      pageDescriptor: {
        pageType: 'business-route',
        openMode: 'workbench-route',
        hostType: 'business-route-host',
        layout: 'workspace',
        target: { route: '/config/applications', query: {} },
        tabPolicy: { identity: 'by-params' },
      },
    },
  });

  expect(wrapper.findComponent(ModuleContextProvider).props('moduleAlias')).toBe('platform.application');
  expect(wrapper.get('[data-testid="static-page"]').text()).toBe('workspace');
});

it('rebuilds a route runtime only for an explicit refresh', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/iam/users', component: { template: '<div />' }, meta: { moduleAlias: 'iam.user' } }],
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

  await wrapper.setProps({ refreshRevision: 1 });
  await nextTick();

  expect(wrapper.get('[data-testid="page-instance"]').text()).toBe('2');
});

it.each([undefined, 'platform.code_rule'])(
  'protects ordinary static route edits with module context %s through the shared guard',
  async (moduleAlias) => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/platform/code-rule', component: { template: '<div />' }, meta: { moduleAlias } }],
    });
    await router.push('/platform/code-rule');
    const descriptor = withPageInstanceKey(
      {
        pageType: 'business-route',
        openMode: 'workbench-route',
        hostType: 'business-route-host',
        target: { route: '/platform/code-rule' },
        tabPolicy: { identity: 'by-target' },
      } satisfies BusinessRoutePageDescriptor,
      `static-edit-${moduleAlias ?? 'no-module'}`,
    );
    const dirty = ref(false),
      busy = ref(false);
    // eslint-disable-next-line vue/one-component-per-file -- local editable route contract.
    const Editor = defineComponent({
      setup() {
        useWorkspaceViewUnsavedState(
          '规则草稿',
          () => dirty.value,
          () => busy.value,
        );
        return () =>
          h('input', {
            onInput: () => {
              dirty.value = true;
            },
          });
      },
    });
    const wrapper = mount(StaticRoutePageHost, {
      props: { component: Editor, route: router.currentRoute.value, pageDescriptor: descriptor },
    });
    const keys = [tabKeyOf(descriptor)];
    try {
      expect(hasWorkspaceViewDiscardProtection(keys)).toBe(false);
      await wrapper.get('input').setValue('new rule');
      expect(hasWorkspaceViewDiscardProtection(keys)).toBe(true);
      for (const operation of ['close', 'refresh', 'logout'] as const) {
        feedback.confirm.mockResolvedValueOnce(false);
        expect(await confirmDiscardWorkspaceViewState(keys, operation)).toBe(false);
        feedback.confirm.mockResolvedValueOnce(true);
        expect(await confirmDiscardWorkspaceViewState(keys, operation)).toBe(true);
      }
      dirty.value = false;
      busy.value = true;
      for (const operation of ['close', 'refresh', 'logout'] as const) {
        feedback.confirm.mockClear();
        expect(await confirmDiscardWorkspaceViewState(keys, operation)).toBe(false);
        expect(feedback.confirm).not.toHaveBeenCalled();
      }
      // Refresh must release the replaced component's busy registration for this same page key.
      await wrapper.setProps({ refreshRevision: 1 });
      busy.value = false;
      expect(hasWorkspaceViewDiscardProtection(keys)).toBe(false);
      dirty.value = true;
      expect(hasWorkspaceViewDiscardProtection(keys)).toBe(true);
      expect(workspaceViewUnsavedStateSources(keys[0]!)).toEqual(['规则草稿']);
    } finally {
      wrapper.unmount();
      feedback.confirm.mockReset();
      feedback.error.mockReset();
    }
    expect(hasWorkspaceViewDiscardProtection(keys)).toBe(false);
  },
);

it('delegates static page workspace navigation to the shared workbench host', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/config', component: { template: '<div />' } }],
  });
  await router.push('/config');
  const openPage = vi.fn(() => ({ created: true }));
  // eslint-disable-next-line vue/one-component-per-file -- local navigation contract page.
  const Editor = defineComponent({
    setup() {
      const navigation = useModulePageNavigation();
      return () =>
        h(
          'button',
          {
            onClick: () =>
              navigation?.openWorkspaceTab(
                {
                  type: 'config.detail',
                  moduleAlias: 'platform.config',
                  component: { template: '<div />' },
                  titleOf: () => '配置明细',
                  parse: () => ({}),
                },
                {},
                '打开配置',
              ),
          },
          '打开',
        );
    },
  });
  // eslint-disable-next-line vue/one-component-per-file -- local navigation provider.
  const Harness = defineComponent({
    setup() {
      provideWorkbenchNavigation({
        openPage,
        openRoute: () => ({ created: false }),
        replaceRoute: () => ({ created: false }),
        closeCurrentTab: () => ({ created: false }),
        replacePage: vi.fn(),
        closePage: vi.fn(),
        setTabName: vi.fn(),
      });
      return () => h(StaticRoutePageHost, { component: Editor, route: router.currentRoute.value });
    },
  });
  const wrapper = mount(Harness);
  try {
    await wrapper.get('button').trigger('click');
    expect(openPage).toHaveBeenCalledWith(
      expect.objectContaining({
        title: '打开配置',
        target: expect.objectContaining({
          route: '/_platform/workspace/config.detail',
          query: expect.objectContaining({ workspaceView: 'config.detail', workspacePresentation: 'tab' }),
        }),
      }),
    );
  } finally {
    wrapper.unmount();
  }
});
