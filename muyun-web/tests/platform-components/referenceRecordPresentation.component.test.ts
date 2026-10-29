import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { expect, it, vi } from 'vitest';
import { type HttpClient } from '@/web-core';
import { UiSidePanelHost } from '@/vue-ui-antdv';
import { sidePanelHostKey } from '@/vue-ui-antdv/components/sidePanelHost';
import ReferenceRecordDetailBrowser from '@/platform-components/ReferenceRecordDetailBrowser.vue';
import ModuleReferenceRecordDetailBrowser from '@/dynamic-page-runtime/ModuleReferenceRecordDetailBrowser.vue';
import ReadonlyReferenceValue from '@/platform-components/ReadonlyReferenceValue.vue';
import { createReferenceRecordDetailBrowser } from '@/platform-components/referenceRecordDetailBrowser';

function transport() {
  const request = vi.fn(async ({ path }: { path: string }) =>
    path.endsWith('/view-context')
      ? { moduleAlias: 'demo.target', actions: [], capabilities: [] }
      : { id: 'one', title: 'Target' },
  );
  return { request, http: { request } as HttpClient };
}

it('rejects component facade, shared state and reference clicks without a tab host before requesting data', async () => {
  const { request, http } = transport();
  const browser = createReferenceRecordDetailBrowser(http);
  const wrapper = mount(ModuleReferenceRecordDetailBrowser, {
    props: { browser },
    slots: {
      default: () =>
        h(ReadonlyReferenceValue, {
          reference: { targetModuleAlias: 'demo.target', cardinality: 'ONE' },
          value: 'one',
          summary: { id: 'one', title: 'Target' },
        }),
    },
  });
  try {
    const failure = { status: 'rejected', reason: 'missing-host' };
    expect(await wrapper.vm.openRecord('demo.target', 'one')).toEqual(failure);
    expect(await browser.open('demo.target', 'one')).toEqual(failure);
    await wrapper.get('button').trigger('click');
    await flushPromises();
    expect(wrapper.emitted('open-failed')).toEqual([[failure], [failure], [failure]]);
    expect(browser.active.value).toBeUndefined();
    expect(request).not.toHaveBeenCalled();
    expect(document.querySelector('.ant-drawer-open')).toBeNull();
  } finally {
    wrapper.unmount();
  }
});

it.each(['inline', 'viewport', 'host'] as const)(
  'loads records with explicit %s presentation',
  async (mode) => {
    const { request, http } = transport();
    const browser = createReferenceRecordDetailBrowser(http);
    const content = () =>
      h(ReferenceRecordDetailBrowser, {
        browser,
        inlineAnchor: mode === 'inline',
        scope: mode === 'viewport' ? 'viewport' : 'tab',
      });
    const wrapper = mount(
      defineComponent({
        setup: () => () => (mode === 'host' ? h(UiSidePanelHost, {}, { default: content }) : content()),
      }),
      { attachTo: document.body },
    );
    try {
      expect(await browser.open('demo.target', 'one')).toEqual({ status: 'loaded' });
      await flushPromises();
      expect(request).toHaveBeenCalledTimes(2);
      expect(wrapper.findComponent(ReferenceRecordDetailBrowser).emitted('open-failed')).toBeUndefined();
      if (mode === 'host') expect(wrapper.get('.ui-side-panel-host').find('.ant-drawer').exists()).toBe(true);
    } finally {
      wrapper.unmount();
    }
  },
);

it('rechecks the host after an asynchronous leave confirmation and preserves the existing record', async () => {
  const { request, http } = transport();
  const browser = createReferenceRecordDetailBrowser(http);
  const host = ref<HTMLElement | undefined>(document.createElement('section'));
  const wrapper = mount(ReferenceRecordDetailBrowser, {
    props: { browser },
    global: { provide: { [sidePanelHostKey as symbol]: host } },
  });
  try {
    expect(await browser.open('demo.target', 'one')).toEqual({ status: 'loaded' });
    let finish!: (allowed: boolean) => void;
    browser.setBeforeLeave(
      () =>
        new Promise<boolean>((resolve) => {
          finish = resolve;
        }),
    );
    const pending = browser.open('demo.target', 'two');
    host.value = undefined;
    finish(true);
    expect(await pending).toEqual({ status: 'rejected', reason: 'missing-host' });
    expect(browser.active.value?.recordId).toBe('one');
    expect(request).toHaveBeenCalledTimes(2);
  } finally {
    wrapper.unmount();
  }
});

it('can retry after a tab host becomes mounted', async () => {
  const { request, http } = transport();
  const browser = createReferenceRecordDetailBrowser(http);
  const host = ref<HTMLElement>();
  const wrapper = mount(ReferenceRecordDetailBrowser, {
    props: { browser },
    global: { provide: { [sidePanelHostKey as symbol]: host } },
  });
  try {
    expect(await browser.open('demo.target', 'one')).toEqual({ status: 'rejected', reason: 'missing-host' });
    expect(request).not.toHaveBeenCalled();
    host.value = document.createElement('section');
    expect(await browser.open('demo.target', 'one')).toEqual({ status: 'loaded' });
  } finally {
    wrapper.unmount();
  }
});

it('reports a host lost during data loading rather than claiming the presentation succeeded', async () => {
  let finish!: (record: unknown) => void;
  const request = vi.fn(async ({ path }: { path: string }) =>
    path.endsWith('/view-context')
      ? { moduleAlias: 'demo.target', actions: [], capabilities: [] }
      : new Promise((resolve) => {
          finish = resolve;
        }),
  );
  const browser = createReferenceRecordDetailBrowser({ request } as HttpClient);
  const host = ref<HTMLElement | undefined>(document.createElement('section'));
  const wrapper = mount(ReferenceRecordDetailBrowser, {
    props: { browser },
    global: { provide: { [sidePanelHostKey as symbol]: host } },
  });
  try {
    const pending = browser.open('demo.target', 'one');
    await flushPromises();
    host.value = undefined;
    finish({ id: 'one', title: 'One' });
    expect(await pending).toEqual({ status: 'rejected', reason: 'missing-host' });
    expect(wrapper.emitted('open-failed')).toEqual([[{ status: 'rejected', reason: 'missing-host' }]]);
  } finally {
    wrapper.unmount();
  }
});
