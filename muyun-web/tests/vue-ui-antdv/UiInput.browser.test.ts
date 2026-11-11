import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import UiInput from '@/vue-ui-antdv/components/UiInput.vue';
import '@/vue-ui-antdv/styles.css';

it('supports Tab, Enter and Space with translated names without submitting or changing the value', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const submit = vi.fn((event: Event) => event.preventDefault());
  const keydown = vi.fn();
  const Fixture = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () =>
        h('form', { onSubmit: submit }, [
          h(UiInput, { type: 'password', visibilityToggle: true, value: 'secret', onKeydown: keydown }),
        ]);
    },
  });
  const wrapper = mount(Fixture, { attachTo: document.body });
  try {
    const input = wrapper.get('input').element as HTMLInputElement;
    await page.elementLocator(input).click();
    await userEvent.keyboard('{Tab}');
    const button = wrapper.get('button').element as HTMLButtonElement;
    expect(document.activeElement).toBe(button);
    expect(button.getAttribute('aria-label')).toBe('Show password');
    keydown.mockClear();
    await userEvent.keyboard('{Enter}');
    expect(input.type).toBe('text');
    expect(button.getAttribute('aria-label')).toBe('Hide password');
    expect(button.getAttribute('aria-pressed')).toBe('true');
    await userEvent.keyboard(' ');
    expect(input.type).toBe('password');
    expect(input.value).toBe('secret');
    expect(submit).not.toHaveBeenCalled();
    expect(keydown).not.toHaveBeenCalled();
    language.setLanguage('zh-CN');
    await expect.poll(() => button.getAttribute('aria-label')).toBe('显示密码');
  } finally {
    wrapper.unmount();
  }
});
