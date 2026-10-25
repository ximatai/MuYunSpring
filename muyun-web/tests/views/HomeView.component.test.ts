import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { describe, expect, it } from 'vitest';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import HomeView from '../../src/views/HomeView.vue';

describe('HomeView', () => {
  it.each([
    ['zh-CN', '工作台'],
    ['en', 'Workspace'],
  ])('uses the selected %s language for the workspace label', (languageTag, label) => {
    const language = createLanguageContext({ languageTag });
    const wrapper = mount(
      defineComponent({
        setup() {
          provideLanguageContext(language);
          return () => h(HomeView);
        },
      }),
    );
    expect(wrapper.get('.home-eyebrow').text()).toBe(label);
    wrapper.unmount();
  });
});
