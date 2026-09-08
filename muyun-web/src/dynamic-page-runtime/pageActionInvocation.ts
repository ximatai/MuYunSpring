import type { PageActionInvocation } from '@muyun/web-contracts';
import type { HttpClient } from '@muyun/web-core';

/** Execute only the server-issued transport; action identity is not an HTTP route. */
export async function invokePageAction(
  http: HttpClient,
  invocation: PageActionInvocation | undefined,
  context: { recordId?: string; record?: Record<string, unknown> } = {},
): Promise<unknown> {
  if (!invocation) throw new Error('该动作尚未提供当前区域的执行入口');
  let path = invocation.path;
  if (!path.startsWith('/') || path.startsWith('//')) throw new Error('页面动作调用地址无效');
  if (path.includes('{recordId}')) {
    if (!context.recordId) throw new Error('页面动作需要当前记录');
    path = path.replaceAll('{recordId}', encodeURIComponent(context.recordId));
  }
  if (/[{}]/.test(path)) throw new Error('页面动作存在未绑定参数');
  if (invocation.input === 'FORM_RECORD' && !context.record) throw new Error('表单动作需要当前草稿');
  return http.request({
    method: invocation.method,
    path,
    ...(invocation.input === 'FORM_RECORD' ? { body: { record: context.record } } : {}),
  });
}
