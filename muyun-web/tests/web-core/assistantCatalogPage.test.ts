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

it('distinguishes a complete catalog from a partial first page and the last unread page', () => {
  const records = Array.from({ length: 13 }, (_, index) => ({ fieldName: `field${index}` }));
  const first = pageAssistantCatalog(records);
  expect(first.page).toMatchObject({ coverage: 'partial', nextOffset: 10, total: 13 });
  expect(pageAssistantCatalog(records, 10).page).toMatchObject({ coverage: 'partial', nextOffset: null });
  expect(pageAssistantCatalog(records.slice(10)).page.coverage).toBe('complete');
  expect(pageAssistantCatalog([]).page.coverage).toBe('complete');
});

it('does not report complete coverage when the terminal page skipped an oversized entry', () => {
  expect(pageAssistantCatalog(['large'.repeat(20), 'ok'], 0, 15).page).toMatchObject({
    nextOffset: null,
    oversizedIndexes: [0],
    coverage: 'partial',
  });
});
