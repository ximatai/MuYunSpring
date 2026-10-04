import type { HttpClient } from '../http';
import type { WebQueryRequest } from '@muyun/web-contracts';

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
  errorFileName?: string;
  errorFileToken?: string;
}

/** Dynamic EXCHANGE transport; domain execution remains behind the module's standard action scope. */
export function createDataExchangeClient(http: HttpClient, moduleAlias: string) {
  const base = `/${encodeURIComponent(moduleAlias)}`;
  return {
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
