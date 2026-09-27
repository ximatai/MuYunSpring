/** Model observation only; preparation still validates against the full authoritative catalog. */
export function pageAssistantCatalog<T>(records: readonly T[], offset = 0, budget = 8_000) {
  const items: T[] = [];
  const oversized: number[] = [];
  let remaining = budget;
  let next = Math.min(offset, records.length);
  while (next < records.length && next - offset < 10) {
    const item = records[next]!;
    const size = JSON.stringify(item).length;
    if (size > budget) {
      oversized.push(next++);
      continue;
    }
    if (size > remaining) break;
    remaining -= size;
    items.push(item);
    next++;
  }
  return {
    items,
    page: {
      offset,
      total: records.length,
      nextOffset: next < records.length ? next : null,
      oversizedIndexes: oversized,
      ...(oversized.length
        ? { note: '这些条目的完整定义超过单项读取预算，请通过标准配置界面查看；未将其当作不存在。' }
        : {}),
    },
  };
}
