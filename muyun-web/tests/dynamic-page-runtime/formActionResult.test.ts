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

it.each([null, 'existing-record'])('normalizes dynamic record patches and child drafts for id %s', (id) => {
  const response = {
    body: {
      type: 'OBJECT',
      value: {
        recordPatch: {
          id,
          version: 9,
          values: { total: '12345678901234567890.12' },
          children: {
            lines: [
              { id: null, version: null, values: { quantity: 3 }, children: {} },
              { id: 'existing-line', version: 2, values: { quantity: 4 }, children: {} },
            ],
          },
        },
      },
    },
  };
  const patch = formActionResult(response).recordPatch;
  expect({ id: 'draft-id', version: 1, title: 'kept', ...patch }).toEqual({
    id: 'draft-id',
    version: 1,
    title: 'kept',
    total: '12345678901234567890.12',
    lines: [
      { id: null, version: null, quantity: 3 },
      { id: 'existing-line', version: 2, quantity: 4 },
    ],
  });
  expect(patch).not.toHaveProperty('values');
  expect(patch).not.toHaveProperty('children');
});

it('preserves fields and child collections omitted by a partial dynamic patch', () => {
  const draft = {
    id: 'draft',
    version: 3,
    title: 'unsaved',
    lines: [{ id: 'line', quantity: 7 }],
  };
  const { recordPatch } = formActionResult({
    recordPatch: { id: null, version: null, values: { total: 42 }, children: {} },
  });
  expect({ ...draft, ...recordPatch }).toEqual({ ...draft, total: 42 });
  expect(recordPatch).toEqual({ total: 42 });
});
