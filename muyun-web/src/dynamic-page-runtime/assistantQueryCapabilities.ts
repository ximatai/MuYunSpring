import {
  parseRecordQueryListStandardQuery,
  type RecordQueryListQueryController,
} from '@muyun/platform-components';
import type { AssistantCapability } from '@muyun/web-core';

/** Adapts only list-owned query ports; custom explorers may omit this capability. */
export function createAssistantQueryCapabilities(
  controller: RecordQueryListQueryController,
): AssistantCapability[] {
  const query = controller.snapshot().standardQuery;
  if (!query || !controller.applyStandardQuery || query.fields.length === 0) return [];
  return [
    {
      descriptor: {
        code: 'query.apply-standard',
        description:
          '按声明的字段和操作符替换当前列表高级筛选与排序（AND 条件）；保留快速搜索和常驻条件。空数组清除对应设置。返回当前页、总数与截断标识。引用字段不接受猜测 ID。',
        inputSchema: {
          type: 'object',
          additionalProperties: false,
          required: ['conditions', 'sorts'],
          properties: {
            conditions: {
              type: 'array',
              maxItems: 20,
              items: {
                anyOf: query.fields.map((field) => ({
                  type: 'object',
                  additionalProperties: false,
                  required: ['fieldName', 'operator', 'values'],
                  properties: {
                    fieldName: { const: field.name, description: field.title },
                    operator: { type: 'string', enum: field.operators },
                    values: {
                      type: 'array',
                      maxItems: 100,
                      items: field.options
                        ? { enum: field.options }
                        : {
                            type:
                              field.valueType === 'BOOLEAN'
                                ? 'boolean'
                                : ['INTEGER', 'LONG'].includes(field.valueType)
                                  ? 'integer'
                                  : field.valueType === 'DECIMAL'
                                    ? 'number'
                                    : 'string',
                            description: field.valueType,
                          },
                    },
                  },
                })),
              },
            },
            sorts: {
              type: 'array',
              maxItems: 5,
              items: {
                type: 'object',
                additionalProperties: false,
                required: ['field', 'desc'],
                properties: {
                  field: {
                    type: 'string',
                    enum: query.fields.filter((field) => field.sortable).map((field) => field.name),
                  },
                  desc: { type: 'boolean' },
                },
              },
            },
          },
        },
      },
      parseInput(input) {
        return parseRecordQueryListStandardQuery(input, query.fields);
      },
      async execute(input, context) {
        const current = controller.snapshot().standardQuery;
        if (!current || !controller.applyStandardQuery) throw new Error('Standard query is unavailable');
        const parsed = parseRecordQueryListStandardQuery(input, current.fields);
        let pending!: ReturnType<NonNullable<typeof controller.applyStandardQuery>>;
        context.applyEffect(
          () => {
            pending = (async () => {
              await controller.applyStandardQuery!(parsed);
              return controller.settle(context.cancellationSignal ?? context.signal);
            })();
          },
          () => pending.then(() => undefined),
        );
        return pending;
      },
    },
  ];
}
