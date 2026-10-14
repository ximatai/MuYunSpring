import { createLanguageContext } from '@/web-core';
import { Input } from 'ant-design-vue';
import { vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { Modal } from 'ant-design-vue';
import { defineComponent, nextTick, type VNode } from 'vue';
import { expect, it } from 'vitest';
import { createConfirmAction } from '@/vue-ui-antdv/confirm';

it('keeps the review summary visible and technical details collapsed without changing cancellation', async () => {
  let options!: Parameters<typeof Modal.confirm>[0];
  const confirm = createConfirmAction((value) => {
    options = value;
    return { destroy() {}, update() {} };
  });
  const result = confirm({
    title: '确认保存字段',
    content: '新增「姓名」：必填\n警告：需要刷新生效',
    details: { title: '查看详细配置和影响', lines: ['字段 title', '建立索引'] },
  });
  const wrapper = mount(defineComponent({ render: options.content as () => VNode }));
  expect(wrapper.get('p').text()).toContain('姓名');
  expect(wrapper.get('p').text()).toContain('需要刷新生效');
  expect(wrapper.get('details').attributes('open')).toBeUndefined();
  expect(wrapper.get('summary').text()).toBe('查看详细配置和影响');
  expect(wrapper.get('details').text()).toContain('建立索引');
  options.onCancel?.();
  expect(await result).toBe(false);
  wrapper.unmount();
});

it('updates display text in the supplied language while preserving the typed confirmation guard', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  let options!: Parameters<typeof Modal.confirm>[0];
  const update = vi.fn();
  const confirm = createConfirmAction((value) => {
    options = value;
    return { destroy() {}, update };
  });
  const result = confirm(
    {
      title: 'initial',
      requiredText: '原始业务名称',
      danger: true,
      presentation: () => ({
        title: language.t('platform.screen.useModulePageSession.discard-unsaved-changes', '放弃未保存更改'),
        content: language.t(
          'platform.screen.useModulePageSession.this-record-has-unsaved-changes-that-will-be-lost-when',
          '当前记录存在未保存的更改，关闭后将丢失。是否继续？',
        ),
        details: { title: '原始业务名称', lines: ['private-id'] },
      }),
    },
    language,
  );
  const wrapper = mount(defineComponent({ render: options.content as () => VNode }));
  expect(options.title).toBe('Discard unsaved changes');
  expect(wrapper.text()).toContain('This record has unsaved changes');
  wrapper.findComponent(Input).vm.$emit('update:value', '原始');
  await nextTick();
  await expect(options.onOk?.()).rejects.toThrow('Confirmation text does not match');
  language.setLanguage('zh-CN');
  await nextTick();
  expect(update.mock.calls.at(-1)?.[0]).toMatchObject({ title: '放弃未保存更改', okText: '确认' });
  expect(wrapper.findComponent(Input).props('value')).toBe('原始');
  expect(wrapper.findComponent(Input).props('placeholder')).toBe('原始业务名称');
  wrapper.findComponent(Input).vm.$emit('update:value', '原始业务名称');
  await nextTick();
  expect(update.mock.calls.at(-1)?.[0]).toMatchObject({ okButtonProps: { danger: true, disabled: false } });
  options.onOk?.();
  expect(await result).toBe(true);
  options.afterClose?.();
  const updateCount = update.mock.calls.length;
  language.setLanguage('en');
  await nextTick();
  expect(update).toHaveBeenCalledTimes(updateCount);
  wrapper.unmount();
});
