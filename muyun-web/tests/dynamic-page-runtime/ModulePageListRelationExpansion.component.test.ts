import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent } from 'vue';
import type { ModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';
import type { ResolvedModuleUiDescriptor, ResolvedDetailRelationDescriptor } from '@muyun/web-contracts';
import ModulePageListRelationExpansion from '../../src/dynamic-page-runtime/ModulePageListRelationExpansion.vue';

const Detail = defineComponent({
  props: ['parentRecord'],
  template: '<div data-test="rows">{{ parentRecord.lines }}</div>',
});

function setup(request = vi.fn().mockResolvedValue({ records: [{ id: 'retained-child' }] })) {
  const context = {
    moduleAlias: 'test.order',
    http: { request },
  } as unknown as ModuleContext<QueryListRecord>;
  const wrapper = mount(ModulePageListRelationExpansion, {
    props: {
      sourceContext: context,
      uiDescriptor: {} as ResolvedModuleUiDescriptor,
      record: { id: 'parent' },
      relation: { code: 'lines', embeddedField: 'lines' } as ResolvedDetailRelationDescriptor,
      expansion: { relationCode: 'lines', fields: ['title'] },
    },
    global: { stubs: { ModulePageDetailRelations: Detail, RecordPanelState: true } },
  });
  return { wrapper, request, context };
}

describe('aggregate relation expansion visibility', () => {
  it('uses the retained endpoint when the same row changes to recycle-bin mode', async () => {
    const { wrapper, request } = setup();
    await flushPromises();
    expect(request).toHaveBeenLastCalledWith({ path: '/test.order/view/parent/relations/lines/expansion' });
    await wrapper.setProps({ retained: true });
    await flushPromises();
    expect(request).toHaveBeenLastCalledWith({
      path: '/test.order/recycle-bin/view/parent/relations/lines/expansion',
    });
    expect(wrapper.get('[data-test="rows"]').text()).toContain('retained-child');
  });

  it('discards the previous mode response after a retained request finishes', async () => {
    let completeActive!: (value: unknown) => void;
    const request = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            completeActive = resolve;
          }),
      )
      .mockResolvedValueOnce({ records: [{ id: 'retained-child' }] });
    const { wrapper } = setup(request);
    await wrapper.setProps({ retained: true });
    await flushPromises();
    completeActive({ records: [{ id: 'active-child' }] });
    await flushPromises();
    expect(wrapper.get('[data-test="rows"]').text()).toContain('retained-child');
    expect(wrapper.text()).not.toContain('active-child');
  });

  it('invalidates pending results when the parent disappears or tenant context is replaced', async () => {
    let complete!: (value: unknown) => void;
    const request = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            complete = resolve;
          }),
      )
      .mockResolvedValue({ records: [{ id: 'new-context-child' }] });
    const { wrapper, context } = setup(request);
    await wrapper.setProps({ record: {} });
    complete({ records: [{ id: 'old-child' }] });
    await flushPromises();
    expect(wrapper.find('[data-test="rows"]').exists()).toBe(false);
    await wrapper.setProps({ record: { id: 'parent' }, sourceContext: { ...context } });
    await flushPromises();
    expect(wrapper.get('[data-test="rows"]').text()).toContain('new-context-child');
  });
});
