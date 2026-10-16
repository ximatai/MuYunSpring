import { shallowMount, flushPromises } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it } from 'vitest';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import EmployeeEmploymentTable from '@/views/EmployeeEmploymentTable.vue';

it('updates employee headers without regrouping business records or resetting expanded rows and selection', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const rows = [
    {
      id: 'employment-original',
      employeeId: 'employee-original',
      employeeTitle: '职员原名',
      positionId: 'position-original',
      positionTitle: '岗位原名',
      organizationId: 'organization-original',
    },
  ];
  const selectedIds = new Set(['employment-original']);
  const wrapper = shallowMount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(EmployeeEmploymentTable, { rows, selectedIds, selectable: true });
      },
    }),
    { global: { stubs: { EmployeeEmploymentTable: false } } },
  );
  const table = wrapper.findComponent(EmployeeEmploymentTable).findComponent({ name: 'UiDataTable' });
  const grouped = table.props('rows');
  await table.vm.$emit('row-dblclick', grouped[0]);
  expect(table.props('expandedRowKeys')).toEqual(['employee-original']);
  expect(table.props('columns').map((column: { title: string }) => column.title)).toContain(
    'Employee number',
  );
  language.setLanguage('zh-CN');
  await flushPromises();
  expect(table.props('columns').map((column: { title: string }) => column.title)).toContain('工号');
  language.setLanguage('en');
  await flushPromises();
  expect(table.props('rows')).toBe(grouped);
  expect(grouped[0].title).toBe('职员原名');
  expect(grouped[0].positions[0]).toEqual(rows[0]);
  expect(table.props('expandedRowKeys')).toEqual(['employee-original']);
  expect([...selectedIds]).toEqual(['employment-original']);
  wrapper.unmount();
});
