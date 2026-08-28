import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import type { ModuleContext } from '@muyun/web-core';
import ModuleActionButton from '@/platform-components/ModuleActionButton.vue';

const context = {
  action: (actionCode: string) => ({ actionCode, available: true, title: '新建实体' }),
  runtime: { snapshot: () => ({}) },
} as unknown as ModuleContext<unknown>;

it('uses the standard compact icon-only action treatment without caller-owned button styling', () => {
  const wrapper = mount(ModuleActionButton, {
    props: {
      context,
      actionCode: 'create',
      iconOnly: true,
      presentation: 'record-explorer-create',
    },
  });

  const button = wrapper.get('button');
  expect(button.attributes('title')).toBe('新建实体');
  expect(button.classes()).toContain('ant-btn-default');
  expect(button.classes()).toContain('record-explorer-panel-create-action');
});

it('preserves the compact icon-only contract for non-create module actions', () => {
  const wrapper = mount(ModuleActionButton, {
    props: {
      context,
      actionCode: 'delete',
      iconOnly: true,
    },
  });

  const button = wrapper.get('button');
  expect(button.classes()).toContain('module-action-button--icon-only');
  expect(button.classes()).not.toContain('record-explorer-panel-create-action');
});

it('does not infer explorer presentation from a create action outside that surface', () => {
  const wrapper = mount(ModuleActionButton, {
    props: {
      context,
      actionCode: 'create',
      iconOnly: true,
    },
  });

  expect(wrapper.get('button').classes()).not.toContain('record-explorer-panel-create-action');
});
