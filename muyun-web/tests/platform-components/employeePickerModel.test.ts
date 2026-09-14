import { expect, it } from 'vitest';
import type { EmployeePickerConfig } from '@/platform-components/employeePickerModel';

it('keeps the employee source boundary separate from user account IDs', async () => {
  const picker: EmployeePickerConfig = {
    searchPage: async () => ({ records: [{ id: 'employee-1', title: '张三' }], total: 1 }),
    resolveEmployees: async (ids) => ids.map((id) => ({ id, title: id })),
  };

  await expect(picker.searchPage({ keyword: '张', pageNum: 1, pageSize: 20 })).resolves.toEqual({
    records: [{ id: 'employee-1', title: '张三' }],
    total: 1,
  });
  await expect(picker.resolveEmployees(['employee-1'])).resolves.toEqual([
    { id: 'employee-1', title: 'employee-1' },
  ]);
});
