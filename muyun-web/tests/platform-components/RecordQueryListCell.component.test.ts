import { shallowMount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import RecordQueryListCell from '@/platform-components/RecordQueryListCell.vue';

it('renders descriptor-derived list types through the shared cell semantics', async () => {
  const wrapper = shallowMount(RecordQueryListCell, {
    props: {
      record: {
        enabled: false,
        published: true,
        tags: [{ id: 'a', title: '平台', color: '#1677FF' }],
        updatedAt: '2026-08-30T09:30:00+08:00',
        fileSize: 1024,
        color: '#1677FF',
      },
      column: { key: 'enabled', title: '启用状态', type: 'enabledStatus' },
    },
  });

  expect(wrapper.findComponent({ name: 'RecordStatusTag' }).props()).toMatchObject({ enabled: false });

  await wrapper.setProps({
    column: {
      key: 'published',
      title: '发布状态',
      type: 'booleanStatus',
      booleanStatus: { trueLabel: '已发布', falseLabel: '未发布', trueTone: 'SUCCESS' },
    },
  });
  expect(wrapper.findComponent({ name: 'RecordStatusTag' }).props()).toMatchObject({
    enabled: true,
    enabledLabel: '已发布',
    disabledLabel: '未发布',
  });

  await wrapper.setProps({ column: { key: 'tags', title: '标签', type: 'tagList' } });
  expect(wrapper.findComponent({ name: 'RecordTagList' }).props('items')).toEqual([
    { id: 'a', title: '平台', color: '#1677FF' },
  ]);

  await wrapper.setProps({ column: { key: 'updatedAt', title: '更新时间', type: 'datetime' } });
  expect(wrapper.findComponent({ name: 'DateTimeText' }).props('value')).toBe('2026-08-30T09:30:00+08:00');

  await wrapper.setProps({ column: { key: 'fileSize', title: '大小', type: 'fileSize' } });
  expect(wrapper.findComponent({ name: 'FileSizeText' }).props('value')).toBe(1024);
});

it('preserves text, option title, custom rendering, color and multiline semantics', async () => {
  const wrapper = shallowMount(RecordQueryListCell, {
    props: {
      record: { ownerId: 'owner-1', ownerTitle: '平台管理员', color: '#1677FF', summary: '默认值' },
      column: { key: 'ownerId', title: '负责人', titleField: 'ownerTitle' },
      cellRenderers: { summary: () => '外部渲染值' },
    },
  });

  expect(wrapper.find('.record-query-list-text').text()).toBe('平台管理员');

  await wrapper.setProps({
    record: { ownerId: 'owner-1', ownerIdTitle: '教学管理员', color: '#1677FF', summary: '默认值' },
    column: { key: 'ownerId', title: '负责人', titleField: 'legacyOwnerTitle' },
  });
  expect(wrapper.find('.record-query-list-text').text()).toBe('教学管理员');

  await wrapper.setProps({
    record: { attendanceStatus: 'ATTENDED', color: '#1677FF', summary: '默认值' },
    column: {
      key: 'attendanceStatus',
      title: '参加状态',
      optionItems: [{ code: 'ATTENDED', title: '已参加', enabled: true }],
    },
  });
  expect(wrapper.find('.record-query-list-text').text()).toBe('已参加');

  await wrapper.setProps({
    record: { attendanceStatuses: '["ATTENDED","ABSENT"]' },
    column: {
      key: 'attendanceStatuses',
      title: '参加状态',
      optionItems: [
        { code: 'ATTENDED', title: '已参加', enabled: true },
        { code: 'ABSENT', title: '缺考', enabled: true },
      ],
    },
  });
  expect(wrapper.find('.record-query-list-text').text()).toBe('已参加、缺考');

  await wrapper.setProps({ record: { attendanceStatuses: ['ABSENT', 'ATTENDED'] } });
  expect(wrapper.find('.record-query-list-text').text()).toBe('缺考、已参加');

  await wrapper.setProps({ column: { key: 'summary', title: '摘要', maxDisplayLines: 3 } });
  expect(wrapper.find('.record-query-list-text').text()).toBe('外部渲染值');
  expect(wrapper.find('.record-query-list-text').attributes('style')).toContain(
    '--record-query-list-max-lines: 3',
  );

  await wrapper.setProps({
    record: { color: '#1677FF' },
    column: { key: 'color', title: '颜色', type: 'colorPicker' },
  });
  expect(wrapper.find('.record-query-list-color').text()).toContain('#1677FF');
  expect(wrapper.find('.record-query-list-color i').attributes('style')).toContain(
    'background-color: rgb(22, 119, 255)',
  );
});
