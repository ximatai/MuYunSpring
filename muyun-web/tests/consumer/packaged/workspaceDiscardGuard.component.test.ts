import { readFileSync } from 'node:fs';
import * as Vue from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';

it('shares registered page state with a custom host through the installed package', async () => {
  const bundle = process.env.MUYUN_CONSUMER_CONTRACT_BUNDLE;
  if (!bundle) throw new Error('Run through npm run verify:consumer.');
  const sdk = new Function('Vue', `${readFileSync(bundle, 'utf8')}\nreturn MuyunConsumerContract;`)(Vue);
  const language = sdk.createLanguageContext({ languageTag: 'zh-CN' });
  const dirty = Vue.ref(false);
  const busy = Vue.ref(false);
  const definition = {
    type: 'consumer.draft',
    route: '/consumer/draft',
    moduleAlias: 'consumer.records',
    presentations: ['tab'],
    titleOf: () => '消费者草稿',
    parse: () => ({}),
    component: Vue.defineComponent({
      setup() {
        sdk.useWorkspaceViewUnsavedState(
          '消费者草稿',
          () => dirty.value,
          () => busy.value,
        );
        return () => Vue.h('div', '消费者页面');
      },
    }),
  };
  sdk.configureWorkspaceViewContributions('consumer-guard-test', [definition]);
  const descriptor = sdk.createWorkspaceViewDescriptor(definition, {});
  const keys = [sdk.tabKeyOf(descriptor)];
  const wrapper = mount(sdk.WorkspaceViewOutlet, { props: { descriptor } });
  const guard = () => sdk.confirmDiscardWorkspaceViewState(keys, 'logout', language);
  const activeDialog = () =>
    [...document.querySelectorAll<HTMLElement>('.ant-modal-confirm')].find(
      (dialog) => dialog.style.display !== 'none',
    );
  const choose = async (text: string) => {
    await flushPromises();
    const dialog = activeDialog()!;
    expect(dialog.textContent).toContain('消费者草稿');
    expect(dialog.querySelector('.ant-modal-confirm-title')?.textContent).toBe('退出登录');
    const button = [...dialog.querySelectorAll('button')].find(
      (item) => item.textContent?.replace(/\s/g, '') === text,
    );
    expect(button).toBeDefined();
    button!.click();
    await flushPromises();
    await vi.waitFor(() => expect(activeDialog()).toBeUndefined());
  };
  try {
    expect(sdk.hasWorkspaceViewDiscardProtection(keys)).toBe(false);
    await expect(guard()).resolves.toBe(true);
    dirty.value = true;
    expect(sdk.hasWorkspaceViewDiscardProtection(keys)).toBe(true);
    const cancelled = guard();
    await choose('取消');
    await expect(cancelled).resolves.toBe(false);
    expect(sdk.hasWorkspaceViewDiscardProtection(keys)).toBe(true);
    const accepted = guard();
    await choose('退出登录');
    await expect(accepted).resolves.toBe(true);
    // Confirmation authorizes destruction; the host still owns the page and its draft facts.
    expect(sdk.hasWorkspaceViewDiscardProtection(keys)).toBe(true);
    dirty.value = false;
    busy.value = true;
    await expect(guard()).resolves.toBe(false);
    expect(sdk.hasWorkspaceViewDiscardProtection(keys)).toBe(true);
    expect(activeDialog()).toBeUndefined();
    wrapper.unmount();
    expect(sdk.hasWorkspaceViewDiscardProtection(keys)).toBe(false);
    await expect(guard()).resolves.toBe(true);
  } finally {
    if (wrapper.exists()) wrapper.unmount();
    sdk.configureWorkspaceViewContributions('consumer-guard-test', []);
  }
});

it.each(['custom-content', 'platform-route'] as const)(
  'protects edits in the %s host using the same public discard guard',
  async (mode) => {
    const bundle = process.env.MUYUN_CONSUMER_CONTRACT_BUNDLE;
    if (!bundle) throw new Error('Run through npm run verify:consumer.');
    const sdk = new Function('Vue', `${readFileSync(bundle, 'utf8')}\nreturn MuyunConsumerContract;`)(Vue);
    const dirty = Vue.ref(false);
    const busy = Vue.ref(false);
    const Editor = Vue.defineComponent({
      setup() {
        sdk.useWorkspaceViewUnsavedState(
          '实施表单',
          () => dirty.value,
          () => busy.value,
        );
        return () =>
          Vue.h('input', {
            onInput: () => {
              dirty.value = true;
            },
          });
      },
    });
    const descriptor = {
      pageType: 'business-route',
      openMode: 'workbench-route',
      hostType: 'business-route-host',
      target: { route: mode === 'platform-route' ? '/platform/code-rule' : '/mr/custom' },
      tabPolicy: { identity: 'by-target' },
    };
    const keys = [sdk.tabKeyOf(descriptor)];
    const wrapper =
      mode === 'custom-content'
        ? mount(sdk.WorkbenchOutlet, { props: { descriptor }, slots: { default: () => Vue.h(Editor) } })
        : mount(sdk.PlatformAdminOutlet, {
            props: { descriptor },
            global: { stubs: { PlatformAdminRouteOutlet: Editor } },
          });
    const language = sdk.createLanguageContext({ languageTag: 'zh-CN' });
    try {
      expect(sdk.hasWorkspaceViewDiscardProtection(keys)).toBe(false);
      await wrapper.get('input').setValue('未保存的修改');
      expect(sdk.hasWorkspaceViewDiscardProtection(keys)).toBe(true);
      const result = sdk.confirmDiscardWorkspaceViewState(keys, 'logout', language);
      await flushPromises();
      const dialog = [...document.querySelectorAll<HTMLElement>('.ant-modal-confirm')].find(
        (item) => item.style.display !== 'none',
      )!;
      expect(dialog.textContent).toContain('实施表单');
      [...dialog.querySelectorAll('button')]
        .find((item) => item.textContent?.replace(/\s/g, '') === '取消')!
        .click();
      await expect(result).resolves.toBe(false);
      dirty.value = false;
      busy.value = true;
      await expect(sdk.confirmDiscardWorkspaceViewState(keys, 'logout', language)).resolves.toBe(false);
    } finally {
      wrapper.unmount();
    }
    expect(sdk.hasWorkspaceViewDiscardProtection(keys)).toBe(false);
  },
);
