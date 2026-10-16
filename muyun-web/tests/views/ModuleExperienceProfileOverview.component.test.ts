import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import {
  configureModuleContext,
  createLanguageContext,
  provideLanguageContext,
  type HttpClient,
} from '@muyun/web-core';
import ModuleExperienceProfileOverview from '@/views/ModuleExperienceProfileOverview.vue';

it('updates presentation and capability descriptions while preserving the selected mode and write contract', async () => {
  const profile = {
    overviewMode: 'list_card',
    mainMetadataVersion: 7,
    mainCapabilities: [],
    publishedRequiredCapabilities: ['APPROVAL'],
  };
  const request = vi.fn(async () => profile);
  configureModuleContext({ http: { request } as HttpClient });
  const language = createLanguageContext({ languageTag: 'en' });
  const Harness = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () =>
        h(ModuleExperienceProfileOverview, { moduleAlias: 'business.order', moduleTitle: '订单原名' });
    },
  });
  const wrapper = mount(Harness, {
    global: {
      stubs: {
        RecordDetailPanel: { template: '<section><slot name="actions" /><slot /></section>' },
        RecordContentSectionHeading: {
          props: ['title', 'subtitle'],
          template: '<header>{{ title }} {{ subtitle }}</header>',
        },
        UiActionButton: {
          props: ['disabled'],
          emits: ['click'],
          template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
        },
        UiCheckbox: { template: '<span><slot /></span>' },
      },
    },
  });
  try {
    await flushPromises();
    const initialRequests = request.mock.calls.length;
    expect(wrapper.text()).toContain('Published pages still require APPROVAL.');
    await wrapper.find('[data-testid="edit-overview"]').trigger('click');
    await wrapper
      .findAll('[role="radio"]')
      .find((button) => button.text().includes('Tree + card'))!
      .trigger('click');
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(wrapper.find('[role="radio"][aria-checked="true"]').text()).toContain('树 + 卡片');
    expect(wrapper.text()).toContain('按父子层级组织记录');
    expect(request).toHaveBeenCalledTimes(initialRequests);
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.find('[role="radio"][aria-checked="true"]').text()).toContain('Tree + card');
    expect(wrapper.text()).toContain('Organize records in a parent-child hierarchy');
    expect(request).toHaveBeenCalledTimes(initialRequests);
    await wrapper.find('[data-testid="save-overview"]').trigger('click');
    await flushPromises();
    expect(request).toHaveBeenLastCalledWith({
      method: 'POST',
      path: '/platform.module/business.order/overview-mode',
      body: {
        overviewMode: 'tree_card',
        expectedMainMetadataVersion: 7,
        capabilitySelections: { TREE: true, SORT: true, APPROVAL: true },
      },
    });
  } finally {
    wrapper.unmount();
  }
});
