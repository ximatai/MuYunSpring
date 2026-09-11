import { describe, expect, it } from 'vitest';
import { formulaExpressionTokens } from '@/platform-components/formulaExpressionTokens';

describe('formulaExpressionTokens', () => {
  const fields = [
    { name: 'amount', label: '金额' },
    { name: 'buyer.name', label: '购买人' },
  ];
  const functions = [{ name: 'PRESENT', title: '已填写', description: '检查字段是否有值' }];

  it('decorates known fields and functions while retaining their exact source ranges', () => {
    const value = '{amount} = PRESENT({buyer.name})';
    expect(formulaExpressionTokens(value, fields, functions)).toEqual([
      { start: 0, end: 8, source: '{amount}', label: '金额', title: '金额（amount）' },
      { start: 11, end: 18, source: 'PRESENT', label: '已填写', title: '检查字段是否有值' },
      { start: 19, end: 31, source: '{buyer.name}', label: '购买人', title: '购买人（buyer.name）' },
    ]);
  });

  it('does not decorate quoted, unknown, or unfinished source text', () => {
    const value = "'{amount} PRESENT(' + {unknown} + {amount} + PRESENT(1)";
    expect(formulaExpressionTokens(value, fields, functions)).toEqual([
      expect.objectContaining({ source: '{amount}', start: value.lastIndexOf('{amount}') }),
      expect.objectContaining({ source: 'PRESENT', start: value.lastIndexOf('PRESENT') }),
    ]);
  });
});

it('keeps tokenizer-compatible quoted boundaries even after a slash-prefixed quote', () => {
  const value = "'x\\\\' {amount} ' + {amount}";
  expect(formulaExpressionTokens(value, [{ name: 'amount', label: '金额' }])).toEqual([
    expect.objectContaining({ start: value.lastIndexOf('{amount}'), source: '{amount}' }),
  ]);
});
