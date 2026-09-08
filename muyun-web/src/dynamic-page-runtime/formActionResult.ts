/** Only an explicit form result may update an unsaved draft. Ordinary action data is not a patch. */
export function formActionResult(response: unknown): {
  recordPatch: Record<string, unknown>;
  message?: string;
} {
  const object = (value: unknown): value is Record<string, unknown> =>
    value !== null && typeof value === 'object' && !Array.isArray(value);
  if (!object(response)) throw new Error('表单动作未返回有效结果');
  const value = object(response.body) ? response.body.value : 'data' in response ? response.data : response;
  if (!object(value) || !('recordPatch' in value)) throw new Error('表单动作未返回字段回填协议');
  if (value.recordPatch != null && !object(value.recordPatch)) throw new Error('表单动作回填格式无效');
  const patch = Object.fromEntries(
    Object.entries(value.recordPatch ?? {}).filter(
      ([key]) => !['id', 'version', '__proto__', 'constructor', 'prototype'].includes(key),
    ),
  );
  return { recordPatch: patch, message: typeof value.message === 'string' ? value.message : undefined };
}
