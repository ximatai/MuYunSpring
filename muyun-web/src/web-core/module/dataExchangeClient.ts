import type { HttpClient } from '../http';
import type { TextPresentation, WebQueryRequest } from '@muyun/web-contracts';
import { actionResultData } from '../actionResult';
import { businessContentReadHeaders } from '../businessContentLanguage';
import type { RecordQueryRequestOptions } from './staticModuleClient';

export type ExchangePurpose = 'DATA' | 'TRANSLATIONS' | 'READING';
export interface ExchangeOptions {
  purpose: ExchangePurpose;
  language: string;
  timeZone?: string;
}
export interface ExchangeCommand {
  documentId?: string;
  previewDigest?: string;
  sourceLanguage?: string;
  resolutionLanguage?: string;
  sheets: Array<{ entityAlias: string; matchField: string; duplicateStrategy: ImportDuplicateStrategy }>;
  confirmations: Array<{ row: string; field: string; input: string; candidateId: string }>;
}
export interface ExchangeRow {
  row: string;
  entity: string;
  relation: string;
  operation: string;
  recordId?: string;
  currentValues: Record<string, unknown>;
  changes: Record<string, unknown>;
  languages: Record<string, { languageTag?: string; expectedRevision?: number }>;
  choices: Array<{
    field: string;
    input: string;
    candidates: Array<{ id: string; title: string; matchedText: string; kind: string }>;
  }>;
  problem?: string;
}
export interface ExchangePreview {
  documentId: string;
  previewDigest: string;
  purpose: ExchangePurpose;
  language?: string;
  sheets: Array<{
    entityAlias: string;
    sheetName: string;
    main: boolean;
    rowCount: number;
    matchFields: string[];
    fieldTitles: Record<string, string>;
  }>;
  rows: ExchangeRow[];
}
export interface ExchangeResult {
  created: number;
  updated: number;
  skipped: number;
  errorCount: number;
  partialSuccess: boolean;
  errorWorkbookBase64?: string;
  rows: ExchangeRow[];
}

export type ImportDuplicateStrategy = 'ERROR' | 'SKIP' | 'OVERWRITE';
export interface ImportSheet {
  sheetName: string;
  entityAlias: string;
  main: boolean;
  rowCount: number;
  fields: Array<{ fieldName: string; title: string; matchKeyCandidate: boolean }>;
}
export interface ImportParseResult {
  sheets: ImportSheet[];
}
export interface ImportExecutionCommand {
  mainSheet: { matchFieldName: string; duplicateStrategy: ImportDuplicateStrategy };
  childSheets: Array<{
    entityAlias: string;
    matchFieldName: string;
    duplicateStrategy: ImportDuplicateStrategy;
  }>;
}
export interface ImportExecutionResult {
  created: number;
  updated: number;
  skipped: number;
  errorCount: number;
  summaries?: Record<
    string,
    { entityAlias: string; created: number; updated: number; skipped: number; errors: number }
  >;
  partialSuccess: boolean;
  message: string;
  messageText?: TextPresentation;
  errorFileName?: string;
  errorFileToken?: string;
}

/** Source-neutral file transport. The legacy v1 methods remain available while consumers migrate. */
export function createDataExchangeClient(http: HttpClient, moduleAlias: string) {
  const base = `/${encodeURIComponent(moduleAlias)}`;
  return {
    fileTemplate: (options: ExchangeOptions) =>
      http.request<Blob>({
        method: 'POST',
        path: `${base}/exchange/files/template`,
        body: options,
        responseType: 'blob',
      }),
    preview(file: File, command?: ExchangeCommand) {
      const body = new FormData();
      body.append('file', file);
      if (command) body.append('command', new Blob([JSON.stringify(command)], { type: 'application/json' }));
      return http.request<ExchangePreview>({ method: 'POST', path: `${base}/exchange/files/preview`, body });
    },
    async importFile(file: File, command: ExchangeCommand) {
      const body = new FormData();
      body.append('file', file);
      body.append('command', new Blob([JSON.stringify(command)], { type: 'application/json' }));
      return actionResultData(
        await http.request<ExchangeResult>({ method: 'POST', path: `${base}/exchange/files/import`, body }),
      );
    },
    exportFile(
      query: WebQueryRequest | undefined,
      options: ExchangeOptions,
      ids?: string[],
      queryOptions?: RecordQueryRequestOptions,
    ) {
      return http.request<Blob>({
        method: 'POST',
        path: `${base}/exchange/files/export`,
        body: { query, options, ids },
        headers: businessContentReadHeaders(queryOptions),
        responseType: 'blob',
      });
    },
    template: () =>
      http.request<Blob>({
        method: 'POST',
        path: `${base}/exchange/template`,
        body: {},
        responseType: 'blob',
      }),
    parse(file: File) {
      const body = new FormData();
      body.append('file', file);
      return http.request<ImportParseResult>({ method: 'POST', path: `${base}/import/parse`, body });
    },
    execute(file: File, command: ImportExecutionCommand) {
      const body = new FormData();
      body.append('command', new Blob([JSON.stringify(command)], { type: 'application/json' }));
      body.append('file', file);
      return http.request<ImportExecutionResult>({ method: 'POST', path: `${base}/import/execute`, body });
    },
    errorFile: (token: string) =>
      http.request<Blob>({
        method: 'POST',
        path: `${base}/import/error-file/${encodeURIComponent(token)}`,
        responseType: 'blob',
      }),
    exportData(request: WebQueryRequest) {
      return http.request<Blob>({
        method: 'POST',
        path: `${base}/export/data`,
        body: { ...request, page: undefined, unpaged: true },
        responseType: 'blob',
      });
    },
  };
}
