import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import { configureModuleContext, type HttpClient } from '@/web-core';
import CurrentUserProfileDialog from '@/app/CurrentUserProfileDialog.vue';
import '@/styles.css';
import 'ant-design-vue/dist/reset.css';

it.each([800, 390])('keeps avatar actions visible and focusable at viewport width %s', async (width) => {
  await page.viewport(width, 900);
  configureModuleContext({
    http: {
      request: async () => ({
        url: '/avatar.png',
        moduleAlias: 'iam.employee',
        capabilities: [],
        actions: [],
      }),
    } as HttpClient,
  });
  const wrapper = mount(CurrentUserProfileDialog, {
    attachTo: document.body,
    props: {
      open: true,
      profile: {
        username: 'buyer',
        employee: { id: 'employee-1', title: '采购员', avatarAssetId: 'avatar-1', contactEditable: true },
      },
    },
    global: {
      stubs: {
        UiModal: defineComponent({
          setup:
            (_, { slots }) =>
            () =>
              h('div', slots.default?.()),
        }),
      },
    },
  });
  try {
    expect(wrapper.find('.single-image-file-reference-field__label').exists()).toBe(false);
    const avatar = wrapper.get('.profile-avatar').element.getBoundingClientRect();
    const preview = wrapper
      .get('.single-image-file-reference-field__preview')
      .element.getBoundingClientRect();
    expect(preview.height).toBe(width === 800 ? 92 : 76);
    for (const name of ['替换图片', '清除']) {
      const action = page.getByRole('button', { name, exact: true });
      await expect.element(action).toBeVisible();
      const button = wrapper.get(`button[aria-label="${name}"]`).element as HTMLButtonElement;
      button.focus();
      await expect.element(action).toHaveFocus();
      const bounds = button.getBoundingClientRect();
      expect(bounds.top).toBeGreaterThanOrEqual(preview.bottom);
      expect(bounds.left).toBeGreaterThanOrEqual(avatar.left);
      expect(bounds.right).toBeLessThanOrEqual(avatar.right + 1);
    }
  } finally {
    wrapper.unmount();
  }
});
