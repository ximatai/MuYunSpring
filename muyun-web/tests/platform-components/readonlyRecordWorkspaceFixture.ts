import type { ResolvedModuleUiDescriptor, WebPageResponse } from '@/web-contracts';
import type { RecordReadView } from '@/web-core/recordReadView';

export function readWorkspaceFixture(viewCode = 'reception'): RecordReadView {
  const fields = [{ fieldRef: { fieldName: 'title' }, label: '事项', width: '220px', valueType: 'STRING' }];
  const descriptor = {
    page: {
      list: { searchPlaceholder: '搜索事项', fields: { title: '业务读取', fields } },
      detail: {
        display: {
          fields: [...fields, { fieldRef: { fieldName: 'summary' }, label: '摘要', valueType: 'STRING' }],
        },
      },
    },
  } as unknown as ResolvedModuleUiDescriptor;
  return {
    sourceId: 'example.services',
    viewCode,
    scopeKey: 'tenant-a:user-a',
    descriptor,
    recordKey: (row) => `${row.origin}:${row.id}`,
    async query(request) {
      return {
        records: [
          { id: 'same-id', origin: 'call', title: `${viewCode} A` },
          { id: 'same-id', origin: 'visit', title: `${viewCode} B` },
        ],
        pageNum: request.page?.pageNum ?? 1,
        pageSize: request.page?.pageSize ?? 20,
        total: 41,
        pages: 3,
        totalKnown: true,
      } as WebPageResponse<Record<string, unknown>>;
    },
    detail: {
      title: (row) => String(row.title),
      load: async (row) => ({ title: row.title, summary: `Summary for ${row.origin}` }),
    },
  };
}

export function readQuerySchema(): import('@/web-contracts').QuerySchema {
  return {
    scopeName: 'example.services',
    criteriaComposition: 'TREE',
    quickSearch: { enabled: true, fields: ['title'], fieldSchemas: [] },
    fields: [
      {
        name: 'title',
        title: '事项',
        valueType: 'STRING',
        operators: ['EQ', 'LIKE'],
        defaultOperator: 'EQ',
        sortable: true,
      },
      { name: 'state', title: '状态', valueType: 'STRING', operators: ['EQ'], defaultOperator: 'EQ' },
      {
        name: 'ownerId',
        title: '负责人',
        valueType: 'STRING',
        operators: ['EQ'],
        reference: { targetModuleAlias: 'example.person', cardinality: 'ONE' },
      },
    ],
    externalCriteria: [],
    defaultSorts: [{ field: 'title', desc: false }],
  };
}
