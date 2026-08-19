import type { OptionItemDescriptor } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';

/**
 * Shares the runtime option catalog between form and detail surfaces of one module context.
 * Option values remain server-resolved because dictionary scope is tenant-sensitive.
 */
const optionRequests = new WeakMap<ModuleContext<unknown>, Map<string, Promise<OptionItemDescriptor[]>>>();

export function loadOptionFieldItems(
  context: ModuleContext<unknown>,
  fieldName: string,
): Promise<OptionItemDescriptor[]> {
  let requests = optionRequests.get(context);
  if (!requests) {
    requests = new Map();
    optionRequests.set(context, requests);
  }
  const existing = requests.get(fieldName);
  if (existing) {
    return existing;
  }
  const request = context.http.request<OptionItemDescriptor[]>({
    path: `/platform.module/${encodeURIComponent(context.moduleAlias)}/fields/${encodeURIComponent(fieldName)}/options`,
    query: { enabledOnly: false },
  });
  requests.set(fieldName, request);
  return request.catch((error) => {
    requests?.delete(fieldName);
    throw error;
  });
}
