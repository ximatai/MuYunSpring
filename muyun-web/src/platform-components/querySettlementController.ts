/** Shared settlement boundary for query-backed UI surfaces used by orchestration adapters. */
export interface QuerySettlementController<T = void> {
  revision(): number;
  settle(signal?: AbortSignal): Promise<T>;
}
