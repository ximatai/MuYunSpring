import { flushPromises, mount, shallowMount } from '@vue/test-utils';
import { defineComponent, ref } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ModuleBusinessPreview from '@/views/ModuleBusinessPreview.vue';
import { configureModuleContext, createHttpClient } from '@muyun/web-core';
import { provideWorkspaceViewHost } from '@/platform-workbench/workspaceViewHost.ts';

function publishedRuntime(revision = '1') {
  return {
    moduleAlias: 'education.exam',
    tenantRequired: true,
    capabilities: [],
    actions: [],
    uiDescriptor: {
      schemaVersion: revision,
      moduleAlias: 'education.exam',
      page: {
        template: 'LIST_DETAIL_CARD',
        list: { fields: { viewCode: 'list', viewKind: 'LIST', fields: [] } },
        detail: { editor: { viewCode: 'form', viewKind: 'FORM', fields: [] } },
      },
    },
  };
}

describe('ModuleBusinessPreview', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('only mounts the standard host after a published definition is discovered', async () => {
    vi.stubGlobal('fetch', async () =>
      Response.json({
        ...publishedRuntime(),
        uiDescriptor: { schemaVersion: '1', moduleAlias: 'education.exam' },
      }),
    );
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const wrapper = shallowMount(ModuleBusinessPreview, { props: { moduleAlias: 'education.exam' } });
    await flushPromises();

    expect(wrapper.findComponent({ name: 'ModulePageHost' }).exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'UiEmpty' }).props('description')).toContain('尚未发布');
  });

  it('uses the shared standard host without a separate preview tenant selector', async () => {
    vi.stubGlobal('fetch', async () => Response.json(publishedRuntime()));
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const wrapper = shallowMount(ModuleBusinessPreview, {
      props: {
        moduleAlias: 'education.exam',
      },
    });
    await flushPromises();

    expect(wrapper.findComponent({ name: 'ModulePageHost' }).props('descriptor')).toMatchObject({
      target: { moduleAlias: 'education.exam', pageMode: 'LIST' },
    });
    expect(wrapper.find('[aria-label="业务租户"]').exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'ModulePageHost' }).props('requireConfiguredPage')).toBe(true);
  });

  it('blocks preview reload while the shared host edits and resumes it after the edit closes', async () => {
    let revision = '1';
    vi.stubGlobal('fetch', async () => Response.json(publishedRuntime(revision)));
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const wrapper = shallowMount(ModuleBusinessPreview, { props: { moduleAlias: 'education.exam' } });
    await flushPromises();
    const host = wrapper.findComponent({ name: 'ModulePageHost' });
    host.vm.$emit('interaction-state-change', { editing: true, busy: false });
    await flushPromises();
    const reload = wrapper.findComponent({ name: 'UiButton' });
    expect(reload.props('disabled')).toBe(true);

    host.vm.$emit('interaction-state-change', { editing: false, busy: false });
    revision = '2';
    reload.vm.$emit('click');
    await flushPromises();
    expect(reload.props('disabled')).toBe(false);
    expect(wrapper.findComponent({ name: 'ModulePageHost' }).exists()).toBe(true);
  });

  it('registers the host aggregate dirty fact for workbench tab closing', async () => {
    vi.stubGlobal('fetch', async () => Response.json(publishedRuntime()));
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    let registeredDirty: (() => boolean) | undefined;
    let registeredBusy: (() => boolean) | undefined;
    const wrapper = mount(
      defineComponent({
        components: { ModuleBusinessPreview },
        setup() {
          provideWorkspaceViewHost({
            presentation: 'tab',
            setTitle: () => undefined,
            replaceQuery: () => undefined,
            registerUnsavedState: (_source, isDirty, isBusy) => {
              registeredDirty = isDirty;
              registeredBusy = isBusy;
              return () => undefined;
            },
            dismiss: () => undefined,
            close: () => undefined,
          });
        },
        template: '<ModuleBusinessPreview module-alias="education.exam" />',
      }),
      { global: { stubs: { ModulePageHost: true } } },
    );
    await flushPromises();
    const host = wrapper.findComponent({ name: 'ModulePageHost' });

    expect(registeredDirty?.()).toBe(false);
    expect(registeredBusy?.()).toBe(false);
    host.vm.$emit('interaction-state-change', { editing: true, busy: false, dirty: true });
    await flushPromises();
    expect(registeredDirty?.()).toBe(true);

    host.vm.$emit('interaction-state-change', { editing: true, busy: false, dirty: false });
    await flushPromises();
    expect(registeredDirty?.()).toBe(false);
    host.vm.$emit('interaction-state-change', { editing: true, busy: true, dirty: false });
    await flushPromises();
    expect(registeredDirty?.()).toBe(false);
    expect(registeredBusy?.()).toBe(true);
    wrapper.unmount();
  });

  it('preserves the active host when publication changes and reloads only on request', async () => {
    let revision = '1';
    vi.stubGlobal('fetch', async () => Response.json(publishedRuntime(revision)));
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const visible = ref(true);
    const wrapper = mount(
      defineComponent({
        components: { ModuleBusinessPreview },
        setup: () => ({ visible }),
        template:
          '<KeepAlive><ModuleBusinessPreview v-if="visible" module-alias="education.exam" /></KeepAlive>',
      }),
      { global: { stubs: { ModulePageHost: true } } },
    );
    await flushPromises();
    const originalHost = wrapper.findComponent({ name: 'ModulePageHost' });
    const original = originalHost.vm.$.uid;
    visible.value = false;
    await flushPromises();
    revision = '2';
    visible.value = true;
    await flushPromises();
    expect(wrapper.find('[role="status"]').text()).toContain('已更新');
    expect(wrapper.findComponent({ name: 'ModulePageHost' }).vm.$.uid).toBe(original);
    await wrapper.find('button').trigger('click');
    await flushPromises();
    expect(wrapper.find('[role="status"]').exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'ModulePageHost' }).vm.$.uid).toBe(original);
    expect(wrapper.findComponent({ name: 'ModulePageHost' }).props('reloadKey')).toBeGreaterThan(0);
    wrapper.unmount();
  });

  it('retries failed discovery without mounting a business page early', async () => {
    let failed = true;
    vi.stubGlobal('fetch', async () => {
      if (failed) throw new Error('connection lost');
      return Response.json(publishedRuntime());
    });
    configureModuleContext({ httpFactory: () => createHttpClient({ baseUrl: 'http://api.local' }) });
    const wrapper = shallowMount(ModuleBusinessPreview, { props: { moduleAlias: 'education.exam' } });
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'ModulePageHost' }).exists()).toBe(false);
    failed = false;
    wrapper.findComponent({ name: 'UiButton' }).vm.$emit('click');
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    expect(wrapper.findComponent({ name: 'ModulePageHost' }).exists()).toBe(true);
    wrapper.unmount();
  });
});
