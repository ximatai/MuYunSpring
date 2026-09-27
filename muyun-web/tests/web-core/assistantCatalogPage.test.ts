import { expect, it } from 'vitest';
import { pageAssistantCatalog } from '@/web-core';

it('keeps catalog observations bounded without silently losing oversized entries', () => {
  const records = ['oversized'.repeat(20), 'first', 'second', 'third'];
  const first = pageAssistantCatalog(records, 0, 15);
  expect(first.items).toEqual(['first', 'second']);
  expect(first.page).toMatchObject({ total: 4, nextOffset: 3, oversizedIndexes: [0] });
  const next = pageAssistantCatalog(records, first.page.nextOffset!, 15);
  expect(next.items).toEqual(['third']);
  expect(next.page.nextOffset).toBeNull();
  expect(pageAssistantCatalog(records, records.length).items).toEqual([]);
});
