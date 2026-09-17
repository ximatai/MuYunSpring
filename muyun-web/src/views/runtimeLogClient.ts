import { isHttpStreamClient, type HttpClient } from '@muyun/web-core';

const ENDPOINT = '/platform.runtime_log';

export interface RuntimeLogFile {
  name: string;
  sizeBytes: number;
  lastModifiedAt: string;
  active: boolean;
}

export interface RuntimeLogClient {
  download(name: string): Promise<ReadableStream<Uint8Array>>;
  activeStream(tailLines: number, signal?: AbortSignal): Promise<ReadableStream<Uint8Array>>;
}

export function createRuntimeLogClient(http: HttpClient): RuntimeLogClient {
  if (!isHttpStreamClient(http)) {
    throw new Error('当前 HTTP 客户端不支持运行日志流。');
  }
  return {
    download(name) {
      return http.stream({
        path: `${ENDPOINT}/files/${encodeURIComponent(name)}/download`,
        headers: { Accept: 'application/octet-stream' },
      });
    },
    activeStream(tailLines, signal) {
      return http.stream({
        method: 'POST',
        path: `${ENDPOINT}/active/stream`,
        body: { tailLines },
        headers: { Accept: 'text/event-stream, application/json' },
        ...(signal ? { signal } : {}),
      });
    },
  };
}
