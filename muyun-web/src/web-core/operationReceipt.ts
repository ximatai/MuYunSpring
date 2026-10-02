import type { OperationPresentation, OperationReceiptReference } from '@muyun/web-contracts';
import type { HttpClient } from './http';

/** Only query identities survive a conversation. Payloads, permissions and confirmation are never restored. */
export function parseOperationReceiptReference(value: unknown): OperationReceiptReference {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('无效操作查询引用');
  const v = value as Record<string, unknown>;
  const id = (key: string, pattern: RegExp) => typeof v[key] === 'string' && pattern.test(v[key] as string);
  const identifier = /^[a-zA-Z0-9_-]{1,80}$/;
  const module = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/;
  let keys: string[];
  if (v.kind === 'page-publication') {
    keys = ['kind', 'variantId', 'revisionId', 'contentDigest'];
    if (
      !id('variantId', identifier) ||
      !id('revisionId', identifier) ||
      !id('contentDigest', /^[a-f0-9]{64}$/)
    )
      throw new Error('无效页面查询引用');
  } else if (v.kind === 'record-save' || v.kind === 'child-metadata') {
    keys =
      v.kind === 'record-save'
        ? ['kind', 'moduleAlias', 'requestId', 'tenantId', 'menuId']
        : ['kind', 'moduleAlias', 'requestId', 'relationId'];
    if (
      !id('moduleAlias', module) ||
      String(v.moduleAlias).length > 128 ||
      !id('requestId', /^[a-zA-Z0-9-]{16,80}$/) ||
      (v.kind === 'child-metadata' && !id('relationId', identifier)) ||
      (v.tenantId !== undefined && !id('tenantId', identifier)) ||
      (v.menuId !== undefined && !id('menuId', /^[a-zA-Z0-9_.:-]{1,128}$/))
    )
      throw new Error('无效操作查询引用');
  } else throw new Error('不支持的操作查询引用');
  if (Object.keys(v).some((key) => !keys.includes(key))) throw new Error('操作查询引用不能携带执行参数');
  return structuredClone(v) as unknown as OperationReceiptReference;
}
export async function pagePublicationDigest(revision: {
  templateAlias?: string;
  templateVersion?: number;
  uiTreeJson?: string;
}) {
  const bytes = new TextEncoder().encode(
    JSON.stringify([revision.templateAlias, revision.templateVersion, revision.uiTreeJson]),
  );
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), (byte) =>
    byte.toString(16).padStart(2, '0'),
  ).join('');
}
export async function lookupOperationReceipt(
  http: HttpClient,
  input: OperationReceiptReference,
): Promise<OperationPresentation | undefined> {
  const reference = parseOperationReceiptReference(input);
  const e = encodeURIComponent;
  if (reference.kind === 'record-save') {
    const result = await http.request<{ committed: boolean; recordId?: string }>({
      path: `/${e(reference.moduleAlias)}/save-receipts/${e(reference.requestId)}`,
      headers: {
        ...(reference.tenantId ? { 'X-MuYun-Tenant-Id': reference.tenantId } : {}),
        ...(reference.menuId ? { 'X-MuYun-Menu-Id': reference.menuId } : {}),
      },
    });
    return result.committed && result.recordId
      ? {
          title: '原保存已确认',
          lines: ['原请求已提交；当前记录内容可能已有后续修改。', `记录标识：${result.recordId}`],
        }
      : undefined;
  }
  if (reference.kind === 'child-metadata') {
    const result = await http.request<{ relationId: string } | null>({
      path: `/platform.module/${e(reference.moduleAlias)}/metadata-relations/${e(reference.relationId)}/child-metadata-creations/${e(reference.requestId)}`,
    });
    return result?.relationId
      ? { title: '原明细创建已确认', lines: ['原请求已建立明细；请读取当前元数据继续配置。'] }
      : undefined;
  }
  const revision = await http.request<{
    id?: string;
    status?: string;
    templateAlias?: string;
    templateVersion?: number;
    uiTreeJson?: string;
  }>({
    path: `/platform.presentation-variant/${e(reference.variantId)}/revisions/view/${e(reference.revisionId)}`,
  });
  return revision.id === reference.revisionId &&
    ['published', 'archived'].includes(revision.status ?? '') &&
    (await pagePublicationDigest(revision)) === reference.contentDigest
    ? { title: '原页面发布已确认', lines: ['本次确认的修订已经发布；当前业务页面以最新生效修订为准。'] }
    : undefined;
}
