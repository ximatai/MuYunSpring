/** Consecutive ranges preserve configured order when reading down a column, then across columns. */
export function layoutMenuGroups(
  heights: number[],
  maxColumns: number,
  heightBudget: number,
  gap: number,
): Array<{ start: number; end: number }> {
  if (!heights.length) return [];
  const count = heights.length;
  const prefix = [0];
  for (const height of heights) prefix.push(prefix.at(-1)! + height + gap);
  const heightOf = (start: number, end: number) => prefix[end] - prefix[start] - gap;
  const limit = Math.max(1, Math.min(count, Math.floor(maxColumns)));
  // First minimize the tallest column for every possible column count.
  const peaks = Array.from({ length: limit + 1 }, () => Array<number>(count + 1).fill(Infinity));
  peaks[0][count] = 0;
  for (let columns = 1; columns <= limit; columns++) {
    for (let start = count - columns; start >= 0; start--) {
      for (let end = start + 1; end <= count - columns + 1; end++) {
        peaks[columns][start] = Math.min(
          peaks[columns][start],
          Math.max(heightOf(start, end), peaks[columns - 1][end]),
        );
      }
    }
  }
  // Use the fewest columns that fit. Extra width should buy height, not empty space.
  let chosen = 1;
  for (let columns = 1; columns <= limit; columns++) {
    if (peaks[columns][0] < peaks[chosen][0]) chosen = columns;
    if (peaks[columns][0] <= heightBudget) {
      chosen = columns;
      break;
    }
  }
  const peak = peaks[chosen][0];
  // With the optimal height fixed, minimize variance. On ties, fill earlier columns first.
  const costs = Array.from({ length: chosen + 1 }, () => Array<number>(count + 1).fill(Infinity));
  const cuts = Array.from({ length: chosen + 1 }, () => Array<number>(count + 1).fill(-1));
  costs[0][count] = 0;
  for (let columns = 1; columns <= chosen; columns++) {
    for (let start = count - columns; start >= 0; start--) {
      for (let end = count - columns + 1; end > start; end--) {
        const height = heightOf(start, end);
        if (height > peak || !Number.isFinite(costs[columns - 1][end])) continue;
        const cost = height * height + costs[columns - 1][end];
        if (cost < costs[columns][start]) {
          costs[columns][start] = cost;
          cuts[columns][start] = end;
        }
      }
    }
  }
  const ranges: Array<{ start: number; end: number }> = [];
  let start = 0;
  for (let columns = chosen; columns > 0; columns--) {
    const end = cuts[columns][start];
    ranges.push({ start, end });
    start = end;
  }
  return ranges;
}
