import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { createPinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';
import { expect, it } from 'vitest';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import { validateAndCompileMenuRoutes } from '@/app/menuRouteCompiler';
import { useRouteDiagnosticsStore } from '@/app/routeDiagnosticsStore';
import RouteDiagnosticsView from '@/views/RouteDiagnosticsView.vue';

it.each(['declared', 'changed'] as const)(
  'projects verified route diagnostics without changing stored issues (%s)',
  async (kind) => {
    const result = validateAndCompileMenuRoutes(
      [
        {
          id: 'menu-1',
          schemeId: 'a',
          title: '业务菜单原名',
          enabled: true,
          entryType: 'route',
          moduleAlias: 'business.order',
          openMode: 'tab',
          route: '/unregistered',
        },
      ],
      [],
      {},
    );
    const original = result.issues[0]!;
    expect(original.reason).toBe('前端未注册 route：/unregistered');
    if (kind === 'changed') original.reason = '用户补充的诊断原文';
    const pinia = createPinia();
    const store = useRouteDiagnosticsStore(pinia);
    store.replaceIssues(result.issues);
    const stored = store.issues[0];
    const snapshot = JSON.stringify(store.issues);
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/unregistered', component: { template: '<section />' } }],
    });
    await router.push('/unregistered?_muyunMenuId=menu-1');
    const language = createLanguageContext({ languageTag: 'en' });
    const Harness = defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(RouteDiagnosticsView);
      },
    });
    const wrapper = mount(Harness, { global: { plugins: [pinia, router] } });
    try {
      expect(wrapper.text()).toContain(
        kind === 'declared' ? 'The frontend has not registered route: /unregistered' : '用户补充的诊断原文',
      );
      expect(wrapper.text()).toContain('业务菜单原名');
      language.setLanguage('zh-CN');
      await flushPromises();
      expect(wrapper.text()).toContain(original.reason);
      language.setLanguage('en');
      await flushPromises();
      expect(wrapper.text()).toContain(
        kind === 'declared' ? 'The frontend has not registered route: /unregistered' : '用户补充的诊断原文',
      );
      expect(store.issues[0]).toBe(stored);
      expect(JSON.stringify(store.issues)).toBe(snapshot);
    } finally {
      wrapper.unmount();
    }
  },
);
