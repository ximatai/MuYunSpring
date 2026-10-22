import type {
  QueryCriteriaNode,
  QuerySchema,
  WebPageResponse,
  WebQueryRequest,
} from '@ximatai/muyun-web-app';

export type DeviceUseRow = { id: string; origin: string; title: string; status: string; summary: string };

/** Local fixture supports only the two fields/operators advertised here, not a general query engine. */
export const deviceUseQuerySchema: QuerySchema = {
  scopeName: 'example.device_use',
  quickSearch: { enabled: true, fields: ['title'], fieldSchemas: [] },
  fields: [
    { name: 'title', title: '设备事项', valueType: 'STRING', operators: ['EQ', 'LIKE'], sortable: true },
    { name: 'status', title: '领用状态', valueType: 'STRING', operators: ['EQ'], defaultOperator: 'EQ' },
  ],
  externalCriteria: [],
  defaultSorts: [{ field: 'title' }],
  criteriaComposition: 'TREE',
};

function condition(row: DeviceUseRow, field: string, operator = 'EQ', values: unknown[] = []) {
  if (field !== 'title' && field !== 'status') throw new Error(`Unsupported fixture field: ${field}`);
  if (operator === 'EQ') return row[field] === values[0];
  if (field === 'title' && operator === 'LIKE') return row.title.includes(String(values[0] ?? ''));
  throw new Error(`Unsupported fixture operator: ${operator}`);
}
function criteria(row: DeviceUseRow, node: QueryCriteriaNode): boolean {
  if (node.kind === 'CONDITION') return condition(row, node.fieldName, node.operator, node.values);
  return node.operator === 'AND'
    ? node.children.every((child) => criteria(row, child))
    : node.children.some((child) => criteria(row, child));
}

export function queryDeviceUseFixture(request: WebQueryRequest): WebPageResponse<DeviceUseRow> {
  const all = Array.from({ length: 25 }, (_, index) => ({
    id: String(Math.floor(index / 2)),
    origin: index % 2 ? 'visit' : 'call',
    title: `设备事项 ${String(index + 1).padStart(2, '0')}`,
    status: index % 2 ? 'CURRENT' : 'RETURNED',
    summary: '设备交接摘要',
  })).filter(
    (row) =>
      (!request.quickSearch || row.title.includes(request.quickSearch)) &&
      (request.conditions ?? []).every((item) =>
        condition(row, item.fieldName, item.operator, item.values),
      ) &&
      (!request.criteria || criteria(row, request.criteria)),
  );
  const sorts = request.sorts ?? deviceUseQuerySchema.defaultSorts;
  if (sorts.some((sort) => sort.field !== 'title')) throw new Error('Fixture supports title sorting only');
  all.sort((left, right) => {
    for (const sort of sorts) {
      const order = left.title.localeCompare(right.title) * (sort.desc ? -1 : 1);
      if (order) return order;
    }
    // Stable source identity breaks ties before pagination.
    return `${left.origin}:${left.id}`.localeCompare(`${right.origin}:${right.id}`);
  });
  const pageNum = request.page?.pageNum ?? 1;
  const pageSize = request.page?.pageSize ?? 20;
  return {
    records: all.slice((pageNum - 1) * pageSize, pageNum * pageSize),
    total: all.length,
    pageNum,
    pageSize,
    pages: Math.ceil(all.length / pageSize),
    totalKnown: true,
  };
}
