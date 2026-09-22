import { describe, expect, it, vi } from 'vitest';
import { createAssistantQueryCapabilities } from '@/dynamic-page-runtime/assistantQueryCapabilities';
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
    expect(await capability.execute(input, context)).toBe(snapshot);
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
