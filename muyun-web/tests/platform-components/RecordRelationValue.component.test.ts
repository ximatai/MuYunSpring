import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import RecordRelationValue from '@/platform-components/RecordRelationValue.vue';

const field = {
  fieldName: 'ownerId',
  label: '负责人',
  required: false,
  readOnly: true,
  visible: true,
  columnSpan: 1,
  hasOption: false,
  controlType: 'recordPicker' as const,
  reference: { targetModuleAlias: 'iam.user', cardinality: 'ONE' as const, titleField: 'ownerSummary' },
  referenceTitleField: 'ownerSummary',
};
const record = { ownerId: 'user-1', ownerSummary: { id: 'user-1', title: '平台管理员' } };

it('keeps an explicit relation display override ahead of the shared reference browser', () => {
  const wrapper = mount(RecordRelationValue, { props: { field, record, text: '' } });

  expect(wrapper.text()).toBe('');
});

it('uses the shared read-only reference value when a relation has no display override', () => {
  const wrapper = mount(RecordRelationValue, { props: { field, record } });

  expect(wrapper.text()).toContain('平台管理员');
});
