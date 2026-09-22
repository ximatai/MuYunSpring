import { describe, expect, it, vi } from 'vitest';
import {
  assistantQueryResult,
  createAssistantQueryCapabilities,
} from '@/dynamic-page-runtime/assistantQueryCapabilities';
import type {
  RecordQueryListQueryController,
  RecordQueryListQuerySnapshot,
} from '@muyun/platform-components';
import type { AssistantCapabilityExecutionContext } from '@muyun/web-core';

function fixture() {
  const snapshot: RecordQueryListQuerySnapshot = {
    mode: 'normal',
    status: 'ready',
    quickSearchEnabled: false,
    quickSearchFields: [],
    pageNum: 1,
    pageSize: 20,
    total: 40,
    totalKnown: true,
    rows: [],
    truncated: true,
    standardQuery: {
      fields: [{ name: 'amount', title: 'Amount', valueType: 'DECIMAL', operators: ['GT'], sortable: true }],
      conditions: [],
      sorts: [],
    },
  };
  const controller: RecordQueryListQueryController = {
    revision: () => 1,
    snapshot: () => snapshot,
    settle: vi.fn(async () => snapshot),
    applyQuickSearch: vi.fn(async () => snapshot),
    applyStandardQuery: vi.fn(async () => snapshot),
  };
  const context: AssistantCapabilityExecutionContext = {
    signal: new AbortController().signal,
    cancellationSignal: new AbortController().signal,
    isCurrent: () => true,
    commitInternalState: (commit) => commit(),
    applyEffect: vi.fn((effect) => effect()),
  };
  return { snapshot, controller, context };
}

describe('assistant standard query capability', () => {
  it('uses the list port inside an effect and settles with explicit cancellation', async () => {
    const { snapshot, controller, context } = fixture();
    const capability = createAssistantQueryCapabilities(controller)[0]!;
    const input = capability.parseInput({
      conditions: [{ fieldName: 'amount', operator: 'GT', values: [10] }],
      sorts: [],
    });
    expect(await capability.execute(input, context)).toEqual(assistantQueryResult(snapshot));
    expect(context.applyEffect).toHaveBeenCalledOnce();
    expect(controller.settle).toHaveBeenCalledWith(context.cancellationSignal);
    expect(controller.applyStandardQuery).toHaveBeenCalledWith({
      conditions: [{ kind: 'CONDITION', fieldName: 'amount', operator: 'GT', values: [10] }],
      sorts: [],
    });
  });
  it('revalidates current descriptors and never modifies a changed surface', async () => {
    const { snapshot, controller, context } = fixture();
    const capability = createAssistantQueryCapabilities(controller)[0]!;
    const parsed = capability.parseInput({ conditions: [], sorts: [{ field: 'amount', desc: false }] });
    snapshot.standardQuery!.fields = [];
    await expect(capability.execute(parsed, context)).rejects.toThrow();
    expect(context.applyEffect).not.toHaveBeenCalled();
    expect(controller.applyStandardQuery).not.toHaveBeenCalled();
  });
  it('does not publish the capability without a supported query port', () => {
    const { controller } = fixture();
    delete controller.applyStandardQuery;
    expect(createAssistantQueryCapabilities(controller)).toEqual([]);
  });
  it('propagates effect cancellation before querying', async () => {
    const { controller, context } = fixture();
    context.applyEffect = () => {
      throw new DOMException('cancelled', 'AbortError');
    };
    const capability = createAssistantQueryCapabilities(controller)[0]!;
    await expect(capability.execute({ conditions: [], sorts: [] }, context)).rejects.toThrow('cancelled');
    expect(controller.applyStandardQuery).not.toHaveBeenCalled();
  });
});

it('shares column metadata without losing values and reduces a representative result payload', () => {
  const { snapshot } = fixture();
  snapshot.rows = Array.from({ length: 20 }, (_, row) => ({
    id: String(row),
    cells: Array.from({ length: 8 }, (_, column) => ({
      fieldName: `businessField${column}`,
      title: `业务字段${column}`,
      value: `value-${row}-${column}`,
    })),
  }));
  const result = assistantQueryResult(snapshot);
  expect(result.columns).toHaveLength(8);
  expect(result.standardQuery).not.toHaveProperty('fields');
  expect(result.rows[0]?.values).toEqual(snapshot.rows[0]?.cells.map((cell) => cell.value));
  expect(JSON.stringify(result).length).toBeLessThan(JSON.stringify(snapshot).length * 0.5);
  expect(result).toMatchObject({ total: 40, truncated: true });
});

it('shares schema structure while retaining per-field operators and value types', () => {
  const { snapshot, controller } = fixture();
  snapshot.standardQuery!.fields = Array.from({ length: 20 }, (_, index) => ({
    name: `amount${index}`,
    title: `金额${index}`,
    valueType: 'DECIMAL',
    operators: ['GT'],
    sortable: true,
  }));
  const schema = createAssistantQueryCapabilities(controller)[0]!.descriptor.inputSchema as {
    properties: {
      conditions: {
        items: {
          required: string[];
          additionalProperties: boolean;
          anyOf: Array<{ properties: Record<string, Record<string, unknown>> }>;
        };
      };
    };
  };
  const items = schema.properties.conditions.items;
  expect(items.required).toEqual(['fieldName', 'operator', 'values']);
  expect(items.additionalProperties).toBe(false);
  expect(items.anyOf[0]!.properties.values).toMatchObject({ items: { type: 'number' } });
  expect(items.anyOf[0]!.properties.operator).toMatchObject({ enum: ['GT'] });
  const expanded = items.anyOf.map((branch) => ({
    type: 'object',
    additionalProperties: false,
    required: items.required,
    properties: {
      ...branch.properties,
      values: { type: 'array', maxItems: 100, ...branch.properties.values },
    },
  }));
  expect(JSON.stringify(items).length).toBeLessThan(JSON.stringify({ anyOf: expanded }).length * 0.8);
});
