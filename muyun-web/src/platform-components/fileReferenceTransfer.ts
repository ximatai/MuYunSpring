import type { HttpClient } from '@muyun/web-core';
import type { ResolvedFileReferenceFieldDescriptor } from '@muyun/web-contracts';
import type { FileTransferUploadAccess } from './fileTransferUpload';

interface FileReferenceUploadTicket {
  url?: unknown;
}

/** Issues the module-owned short-lived upload ticket for a declared file-reference field. */
export async function issueFileReferenceUploadAccess(
  http: HttpClient,
  moduleAlias: string,
  definition: ResolvedFileReferenceFieldDescriptor,
  draft: Record<string, unknown>,
  file: File,
  intent: FileReferenceUploadIntent,
): Promise<FileTransferUploadAccess> {
  const ticket = await http.request<FileReferenceUploadTicket>({
    method: 'POST',
    path: `/${encodeURIComponent(moduleAlias)}/file-transfer/upload-ticket`,
    body: {
      relationCode: definition.fieldRef.relationCode,
      fieldName: definition.fieldRef.fieldName,
      draft,
      file: { name: file.name, mediaType: file.type || undefined, sizeBytes: file.size },
      intent,
    },
  });
  if (typeof ticket?.url !== 'string' || !ticket.url.trim()) {
    throw new Error('文件上传凭证未提供上传地址。');
  }
  return { uploadUrl: ticket.url };
}

/** A policy-visible upload fact, derived from the field cardinality and its current binding. */
export type FileReferenceUploadIntent = 'CREATE' | 'APPEND' | 'REPLACE';

export function fileReferenceUploadIntent(
  value: unknown,
  definition: ResolvedFileReferenceFieldDescriptor,
): FileReferenceUploadIntent {
  if (!fileReferenceIds(value).length) return 'CREATE';
  return definition.maxFiles === 1 ? 'REPLACE' : 'APPEND';
}

/** Returns the physical file that a successful single-file replacement makes eligible for deletion. */
export function replacedFileReferenceId(
  value: unknown,
  fileId: string,
  definition: ResolvedFileReferenceFieldDescriptor,
) {
  if (definition.maxFiles !== 1) return undefined;
  const current = fileReferenceIds(value)[0];
  return current && current !== fileId ? current : undefined;
}

/** Extracts the one uploaded file identifier from MuYunFileServer's conventional upload payload. */
export function uploadedFileId(payload: unknown): string {
  const items = recordValue(payload, 'items');
  if (!Array.isArray(items) || items.length !== 1) {
    throw new Error('文件服务上传结果未返回唯一文件标识。');
  }
  const id = recordValue(items[0], 'id');
  if (typeof id !== 'string' || !id.trim()) {
    throw new Error('文件服务上传结果未返回文件标识。');
  }
  return id.trim();
}

/** Applies one successful upload to the draft value without inventing a deletion protocol. */
export function appendUploadedFileReference(
  value: unknown,
  fileId: string,
  definition: ResolvedFileReferenceFieldDescriptor,
): string | string[] {
  if (definition.maxFiles === 1) {
    return fileId;
  }
  const existing = Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : [];
  const next = [...new Set([...existing, fileId])];
  if (next.length > definition.maxFiles) {
    throw new Error(`该字段最多允许上传 ${definition.maxFiles} 个文件。`);
  }
  return next;
}

export function acceptedMediaTypes(definition: ResolvedFileReferenceFieldDescriptor) {
  return definition.allowedMediaTypes.length ? definition.allowedMediaTypes.join(',') : undefined;
}

/** Normalizes persisted draft values so callers can account for already bound files. */
export function fileReferenceIds(value: unknown): string[] {
  if (typeof value === 'string' && value.trim()) {
    return [value];
  }
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string' && !!item.trim())
    : [];
}

function recordValue(value: unknown, field: string): unknown {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>)[field] : undefined;
}
