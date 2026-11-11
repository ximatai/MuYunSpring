import type { ModuleContext, LanguageContext } from '@muyun/web-core';
import type { WebTreeNode } from '@muyun/web-contracts';
import type { PickerConstraint, RecordPickerRecord } from './recordPickerConstraints';
import { firstConstraintMessage } from './recordPickerConstraints';
import type { RecordPickerMode } from './recordPickerModel';
import type {
  ReferencePickerCandidate,
  ReferencePickerPageRequest,
  ReferencePickerProvider,
  ReferencePickerTreeNode,
} from './referencePickerModel';
import {
  defaultTreeRecordMatches,
  defaultTreeRecordTitle,
  filterTreeRecords,
  flattenTreeRecords,
} from './treeRecordModel';

export interface RecordPickerPage {
  records: RecordPickerRecord[];
  total: number;
}

/** Existing record-oriented callers retain their source and their event payloads. */
export interface RecordReferencePickerSource {
  context: ModuleContext<RecordPickerRecord>;
  loadOptions?: (keyword: string) => Promise<RecordPickerRecord[]>;
  loadPage?: (request: ReferencePickerPageRequest) => Promise<RecordPickerPage>;
  loadTree?: () => Promise<WebTreeNode<RecordPickerRecord>[]>;
  resolveOptions?: (values: string[]) => Promise<RecordPickerRecord[]>;
  mode?: RecordPickerMode;
  constraints?: PickerConstraint<RecordPickerRecord>[];
  titleOf?: (record: RecordPickerRecord) => string;
  descriptionOf?: (record: RecordPickerRecord) => string | undefined;
  filterOption?: (record: RecordPickerRecord, keyword: string) => boolean;
}

export interface RecordReferenceCandidate extends ReferencePickerCandidate {
  record?: RecordPickerRecord;
}

export async function createRecordReferencePickerProvider(
  source: RecordReferencePickerSource,
  language: LanguageContext,
): Promise<ReferencePickerProvider<RecordReferenceCandidate>> {
  // Explicit list sources must not depend on a target's tree/runtime capability.
  if (source.mode !== 'list') await source.context.runtime.ready;
  const treeAbility = source.mode === 'list' ? undefined : source.context.abilities.tryTree();
  const known = new Map<string, RecordPickerRecord>();
  let treeRead: Promise<WebTreeNode<RecordPickerRecord>[]> | undefined;
  const titleOf = (record: RecordPickerRecord) =>
    source.titleOf?.(record) ?? defaultTreeRecordTitle(record, undefined, language);
  function remember(records: RecordPickerRecord[]) {
    for (const record of records) if (record.id) known.set(record.id, record);
  }
  function candidate(record: RecordPickerRecord, peers: RecordPickerRecord[] = []): RecordReferenceCandidate {
    return {
      id: record.id!,
      get title() {
        return titleOf(record);
      },
      get subtitle() {
        return source.descriptionOf?.(record);
      },
      get disabled() {
        return (
          record.enabled === false ||
          Boolean(
            source.constraints?.length &&
            firstConstraintMessage(
              record,
              { records: [...known.values(), ...peers] },
              source.constraints ?? [],
              language,
            ),
          )
        );
      },
      projections: record.projections,
      affectPatch: record.affectPatch,
      record,
    };
  }
  function candidates(records: RecordPickerRecord[]) {
    return records.filter((record) => Boolean(record.id)).map((record) => candidate(record, records));
  }
  function readTree() {
    treeRead ??= (
      source.loadTree ? source.loadTree() : treeAbility!.tree().then((response) => response.records)
    )
      .then((nodes) => {
        remember(flattenTreeRecords(nodes));
        return nodes;
      })
      .catch((cause) => {
        treeRead = undefined;
        throw cause;
      });
    return treeRead;
  }
  function nodesOf(
    nodes: WebTreeNode<RecordPickerRecord>[],
  ): ReferencePickerTreeNode<RecordReferenceCandidate>[] {
    return nodes
      .filter((node) => Boolean(node.record.id))
      .map((node) => ({ record: candidate(node.record), children: nodesOf(node.children) }));
  }
  const pending = new Map<string, { read: Promise<RecordPickerPage>; state: { revision: number } }>();
  let pageRevision = 0;
  function readPage(request: ReferencePickerPageRequest): Promise<RecordPickerPage> {
    const key = JSON.stringify(request);
    const existing = pending.get(key);
    if (existing) {
      // Returning to a pending search makes that response current again.
      existing.state.revision = ++pageRevision;
      return existing.read;
    }
    const state = { revision: ++pageRevision };
    const read = (async () => {
      if (treeAbility) {
        const nodes = filterTreeRecords(
          await readTree(),
          request.keyword,
          (record, keyword) =>
            source.filterOption?.(record, keyword) ?? defaultTreeRecordMatches(record, keyword, titleOf),
        );
        const records = flattenTreeRecords(nodes);
        const start = (request.pageNum - 1) * request.pageSize;
        return { records: records.slice(start, start + request.pageSize), total: records.length };
      }
      if (source.loadPage) return source.loadPage(request);
      if (source.loadOptions) {
        const records = await source.loadOptions(request.keyword);
        if (state.revision === pageRevision) remember(records);
        const start = (request.pageNum - 1) * request.pageSize;
        return { records: records.slice(start, start + request.pageSize), total: records.length };
      }
      return source.context.crud.query({
        page: { pageNum: request.pageNum, pageSize: request.pageSize },
        ...(request.keyword ? { quickSearch: request.keyword } : {}),
      });
    })().then((page) => {
      if (state.revision === pageRevision) remember(page.records);
      return page;
    });
    pending.set(key, { read, state });
    void read.finally(() => pending.delete(key)).catch(() => {});
    return read;
  }
  return {
    identity: {
      targetModuleAlias: source.context.moduleAlias,
      source: { kind: 'businessPurpose', id: 'record-picker' },
    },
    async searchPage(request) {
      const page = await readPage(request);
      return { records: candidates(page.records), total: page.total };
    },
    ...(treeAbility ? { loadTree: async () => nodesOf(await readTree()) } : {}),
    async resolve(ids) {
      if (source.resolveOptions) {
        const records = await source.resolveOptions(ids);
        return candidates(records);
      }
      if (treeAbility) await readTree();
      else if (ids.some((id) => !known.has(id))) {
        // Without a source-owned resolver, retain the existing candidate scope. Never fall back
        // to target detail/CRUD reads: those may expose a wider scope than a custom loader.
        await readPage({ keyword: '', pageNum: 1, pageSize: 50, scope: { selections: [] } });
      }
      return ids.map((id) => {
        const record = known.get(id);
        // A partial candidate page cannot prove that an unreturned historical ID is unavailable.
        return record ? candidate(record) : { id, title: id, identifierFallback: true, disabled: true };
      });
    },
  };
}
