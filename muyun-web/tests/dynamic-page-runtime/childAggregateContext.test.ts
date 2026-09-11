import { describe, expect, it } from 'vitest';
import { aggregateChildTriggers, childAggregateRows } from '@/dynamic-page-runtime/childAggregateContext';
import type { ResolvedFormComputeRuleDescriptor, ResolvedModuleUiDescriptor } from '@muyun/web-contracts';

describe('child aggregate form context', () => {
  it('maps complete embedded draft rows to their metadata relation code without changing the draft shape', () => {
    const draft = { contractAmount: 0, lineRows: [{ lineAmount: 399 }, { lineAmount: 3897 }] };

    expect(childAggregateRows(draft, descriptor())).toEqual({
      lines: [{ lineAmount: 399 }, { lineAmount: 3897 }],
    });
    expect(childAggregateRows({ contractAmount: 0 }, descriptor())).toBeUndefined();
    expect(childAggregateRows(draft, descriptor(), new Set(['lines']))).toBeUndefined();
  });

  it('notifies only the aggregate rules that read the changed embedded relation', () => {
    const rules = [
      rule('contractAmountSum', ['lines.lineAmount']),
      rule('otherTotal', ['otherRows.amount']),
      rule('label', ['contractName']),
    ];

    expect(aggregateChildTriggers('lineRows', descriptor(), rules)).toEqual(['lines.lineAmount']);
    expect(aggregateChildTriggers('unknownRows', descriptor(), rules)).toEqual([]);
  });
});

function descriptor(): ResolvedModuleUiDescriptor {
  return {
    detailRelations: [
      {
        code: 'line_rows',
        readOnly: false,
        sourceModuleAlias: 'sales.contract',
        sourceEntityAlias: 'contract',
        targetModuleAlias: 'sales.contract',
        targetEntityAlias: 'contract_line',
        parentBinding: 'lines',
        embeddedField: 'lineRows',
        refreshOnDetailReload: true,
      },
    ],
  } as ResolvedModuleUiDescriptor;
}

function rule(code: string, triggerFields: string[]): ResolvedFormComputeRuleDescriptor {
  return {
    code,
    targetField: 'contractAmount',
    targetValueType: 'DECIMAL',
    triggerFields,
    writePolicy: 'ALWAYS',
    program: {
      schemaVersion: 1,
      profile: 'FORM_COMPUTE',
      root: { kind: 'ASSIGN', operator: '=', arguments: [] },
      referencedFields: [],
    },
  };
}
