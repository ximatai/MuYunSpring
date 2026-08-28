import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import RecordExplorerCreateButton from '@/platform-components/RecordExplorerCreateButton.vue';

it('uses the neutral explorer action treatment by default', () => {
  const wrapper = mount(RecordExplorerCreateButton, { props: { title: '新建实体' } });
  const button = wrapper.get('button');

  expect(button.attributes('title')).toBe('新建实体');
  expect(button.classes()).toContain('ant-btn-default');
  expect(button.classes()).not.toContain('ant-btn-primary');
  expect(button.classes()).toContain('record-explorer-panel-create-action');
});
