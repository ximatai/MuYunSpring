import type { WebPageResponse, WebQueryRequest } from '@muyun/web-contracts';

/** Collects a complete navigation result through the existing authorized, paged query protocol. */
export async function loadExplorerRecords<T>(
  query: (request: WebQueryRequest) => Promise<WebPageResponse<T>>,
  request: WebQueryRequest,
  current: () => boolean,
): Promise<WebPageResponse<T> | undefined> {
  const records: T[] = [];
  let businessContent: WebPageResponse<T>['businessContent'];
  let pageSize = request.page?.pageSize ?? 200;
  for (let pageNum = 1; current(); pageNum += 1) {
    const response = await query({ ...request, page: { pageNum, pageSize } });
    if (!current()) return undefined;
    if (response.pageNum !== pageNum || response.pageSize <= 0) {
      throw new Error('Invalid navigation query page');
    }
    records.push(...response.records);
    if (response.businessContent) {
      if (businessContent && businessContent.language !== response.businessContent.language) {
        throw new Error('Navigation query reading language changed between batches');
      }
      businessContent = {
        ...response.businessContent,
        // Coverage is query-wide; only the reading map is scoped to each transport batch.
        readings: { ...businessContent?.readings, ...response.businessContent.readings },
      };
    }
    pageSize = response.pageSize;
    if (
      response.records.length === 0 ||
      (response.totalKnown !== false ? pageNum >= response.pages : response.records.length < pageSize)
    ) {
      return {
        ...response,
        records,
        total: records.length,
        totalKnown: true,
        pageNum: 1,
        pageSize: Math.max(1, records.length),
        pages: records.length ? 1 : 0,
        businessContent,
      };
    }
  }
  return undefined;
}
