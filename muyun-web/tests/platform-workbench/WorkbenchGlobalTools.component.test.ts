import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import WorkbenchGlobalTools from '@/platform-workbench/WorkbenchGlobalTools.vue';

it('keeps global tools accessible and delegates commands without owning authentication or assistant state', async () => {
  const tools = mount(WorkbenchGlobalTools, {
    props: {
      currentUser: { userId: 'admin', username: 'admin', system: true },
      assistantAvailable: true,
      assistantOpen: false,
    },
  });
  const assistant = tools.get('button[aria-label="AI 助手"]');
  expect(assistant.attributes('aria-pressed')).toBe('false');
  await assistant.trigger('click');
  expect(tools.emitted('toggleAssistant')).toEqual([[]]);
  await tools.setProps({ assistantOpen: true });
  expect(assistant.attributes('aria-pressed')).toBe('true');
  const menu = tools.findComponent({ name: 'UiDropdown' });
  expect(menu.props('items').every((item: { iconName?: string }) => Boolean(item.iconName))).toBe(true);
  expect(menu.props('items')).toContainEqual(expect.objectContaining({ key: 'themeSkin', iconName: 'skin' }));
  menu.vm.$emit('select', 'themeSkin');
  expect(tools.emitted('userCommand')).toEqual([['themeSkin']]);
  expect(menu.props('items')).toContainEqual({
    key: 'identity',
    title: '系统管理员',
    iconName: 'user',
    disabled: true,
  });
  expect(menu.props('items').some((item: { key: string }) => item.key === 'workspace')).toBe(false);
  expect(menu.props('items')).toContainEqual({
    key: 'languageSettings',
    iconName: 'language',
    title: '语言设置',
  });
  expect(tools.find('button[aria-label="语言设置"]').exists()).toBe(false);
  menu.vm.$emit('select', 'languageSettings');
  await tools.vm.$nextTick();
  const settings = tools.findComponent({ name: 'WorkbenchLanguageSettings' });
  expect(settings.props('open')).toBe(true);
  expect(tools.emitted('userCommand')).toEqual([['themeSkin']]);
  settings.vm.$emit('update:open', false);
  await tools.vm.$nextTick();
  expect(settings.props('open')).toBe(false);
  expect(tools.get('.user-meta strong').text()).toBe('admin');
  expect(tools.get('.user-meta small').text()).toBe('系统管理员');
  await tools.setProps({ employeeName: ' 张三 ' });
  expect(tools.get('.user-meta small').text()).toBe('张三');
  expect(tools.get('.user-button').attributes('title')).toBe('admin · 张三');
  await tools.setProps({ employeeName: '  ' });
  expect(tools.get('.user-meta small').text()).toBe('系统管理员');
  expect(tools.get('.user-button').attributes('aria-haspopup')).toBe('menu');
  expect(tools.get('.user-button').attributes('aria-expanded')).toBe('false');
  expect(tools.get('.user-caret').classes()).not.toContain('user-caret--open');
  await tools.get('.user-button').trigger('click');
  expect(tools.get('.user-button').attributes('aria-expanded')).toBe('true');
  expect(tools.get('.user-caret').classes()).toContain('user-caret--open');
  menu.vm.$emit('select', 'logout');
  expect(tools.emitted('userCommand')?.at(-1)).toEqual(['logout']);
  await tools.setProps({
    currentUser: { userId: 'admin', system: true, tenantId: 'tenant-a' },
    assistantAvailable: false,
  });
  expect(menu.props('items')).toContainEqual({
    key: 'workspace',
    title: '租户：tenant-a',
    iconName: 'app',
    disabled: true,
  });
  expect(tools.find('.workbench-page-refresh').exists()).toBe(false);
  expect(tools.find('button[aria-label="AI 助手"]').exists()).toBe(false);
});
