import type { QuerySchemaReference } from '@muyun/web-contracts';
import { createNavigatorReferenceCrudClient, type HttpClient } from '@muyun/web-core';
import type { ReferencePickerCandidate, ReferencePickerProvider } from './referencePickerModel';
import { unsupportedReferencePickerConfiguration } from './referencePickerReadError';

const REFERENCE_RESOLVE_MAXIMUM_IDS = 100;

export interface QueryReferencePickerProviderOptions {
  /**
   * The HTTP client from the target's REFERENCE module context. The provider constructs its own
   * navigation-only client instead of accepting an arbitrary context CRUD client.
   */
  http: HttpClient;
  reference: QuerySchemaReference;
}

/**
 * Adapts a query schema's ordinary target reference to the shared picker contract.
 *
 * Query schemas deliberately have no source-field resolve contract or dependency semantics. The
 * only permitted candidate transport here is the target module's REFERENCE navigator endpoint;
 * this is intentionally different from both ordinary target CRUD and source-field resolution.
 */
export function createQueryReferencePickerProvider({
  http,
  reference,
}: QueryReferencePickerProviderOptions): ReferencePickerProvider {
  const target = createNavigatorReferenceCrudClient<Record<string, unknown>>(http, {
    moduleAlias: reference.targetModuleAlias,
  });
  return {
    identity: {
      targetModuleAlias: reference.targetModuleAlias,
      source: { kind: 'targetReference', id: `query:${reference.targetModuleAlias}` },
    },
    async searchPage({ keyword, pageNum, pageSize, scope }) {
      if (scope.selections.length > 0) {
        throw unsupportedReferencePickerConfiguration('当前引用目标不支持范围导航');
      }
      const response = await target.query({
        page: { pageNum, pageSize },
        ...(keyword ? { quickSearch: keyword } : {}),
      });
      return { records: response.records.flatMap(candidateOf(reference)), total: response.total };
    },
    async resolve(ids) {
      const requestedIds = [...new Set(ids.filter(Boolean))];
      if (requestedIds.length === 0) return [];
      const candidatesById = new Map<string, ReferencePickerCandidate>();
      for (const batch of batches(requestedIds, REFERENCE_RESOLVE_MAXIMUM_IDS)) {
        const response = await target.translate(batch);
        for (const candidate of response.records.flatMap(candidateOf(reference))) {
          candidatesById.set(candidate.id, candidate);
        }
      }
      return requestedIds.flatMap((id) => {
        const candidate = candidatesById.get(id);
        return candidate ? [candidate] : [];
      });
    },
  };
}

function* batches<T>(values: readonly T[], size: number): Generator<T[]> {
  for (let start = 0; start < values.length; start += size) {
    yield values.slice(start, start + size);
  }
}

function candidateOf(reference: QuerySchemaReference) {
  return (record: Record<string, unknown>): ReferencePickerCandidate[] => {
    const id = stringValue(record.id);
    if (!id) return [];
    const label = reference.labelField ? stringValue(record[reference.labelField]) : undefined;
    return [
      {
        id,
        title: label ?? stringValue(record.title) ?? stringValue(record.code) ?? id,
        disabled: record.enabled === false,
        ...(reference.labelField && label ? { projections: { [reference.labelField]: label } } : {}),
      },
    ];
  };
}

function stringValue(value: unknown) {
  return typeof value === 'string' && value.trim() ? value : undefined;
}
