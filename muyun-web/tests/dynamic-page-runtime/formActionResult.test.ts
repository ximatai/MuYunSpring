import { expect, it } from 'vitest';
import { formActionResult } from '@/dynamic-page-runtime/formActionResult';

it.each([
  { recordPatch: { total: 42, id: 'other', version: 99 }, message: '已试算' },
  { data: { recordPatch: { total: 42, id: 'other', version: 99 }, message: '已试算' } },
  {
    body: {
      type: 'OBJECT',
      value: { recordPatch: { total: 42, id: 'other', version: 99 }, message: '已试算' },
    },
  },
])('reads explicit form results without replacing record identity', (response) => {
  expect(formActionResult(response)).toEqual({ recordPatch: { total: 42 }, message: '已试算' });
});

it('rejects ordinary action data and malformed patches', () => {
  for (const response of [
    { data: { total: 42 } },
    { body: { type: 'OBJECT', value: { total: 42 } } },
    { recordPatch: [] },
  ]) {
    expect(() => formActionResult(response)).toThrow();
  }
  expect(formActionResult({ recordPatch: null }).recordPatch).toEqual({});
});
