import { shallowReactive, toRaw, watch } from 'vue';
import type { OptionItemDescriptor } from '@muyun/web-contracts';
import type { ModuleContext } from '@muyun/web-core';

type OptionContext = Pick<ModuleContext<unknown>, 'moduleAlias' | 'http'>;
export interface OptionFieldCandidateState {
  status: 'idle' | 'loading' | 'ready' | 'error';
  items: OptionItemDescriptor[];
}
interface OptionFieldState extends OptionFieldCandidateState {
  request?: Promise<OptionItemDescriptor[]>;
}

/** One trusted candidate resource per module context, entity and field. */
const catalogs = new WeakMap<OptionContext, Map<string, OptionFieldState>>();
function resource(
  context: OptionContext,
  fieldName: string,
  entityAlias?: string,
  moduleAlias = context.moduleAlias,
) {
  context = toRaw(context);
  let catalog = catalogs.get(context);
  if (!catalog) {
    catalog = new Map();
    catalogs.set(context, catalog);
  }
  const key = JSON.stringify([moduleAlias, entityAlias ?? '', fieldName]);
  let state = catalog.get(key);
  if (!state) {
    state = shallowReactive<OptionFieldState>({ status: 'idle', items: [] });
    catalog.set(key, state);
  }
  return state;
}

/** Reactive read access; reading candidates never initiates an assistant-specific request. */
export function optionFieldState(
  context: OptionContext,
  fieldName: string,
  entityAlias?: string,
  moduleAlias = context.moduleAlias,
): Readonly<OptionFieldCandidateState> {
  return resource(context, fieldName, entityAlias, moduleAlias);
}

export function loadOptionFieldItems(
  context: OptionContext,
  fieldName: string,
  entityAlias?: string,
  moduleAlias = context.moduleAlias,
  forceRefresh = false,
): Promise<OptionItemDescriptor[]> {
  const state = resource(context, fieldName, entityAlias, moduleAlias);
  if (!forceRefresh && state.request) return state.request;
  state.status = 'loading';
  state.items = [];
  const request = Promise.resolve()
    .then(() =>
      context.http.request<OptionItemDescriptor[]>({
        path: `/platform.module/${encodeURIComponent(moduleAlias)}/fields/${encodeURIComponent(fieldName)}/options`,
        query: { enabledOnly: false, ...(entityAlias ? { entityAlias } : {}) },
      }),
    )
    .then(
      (items) => {
        if (state.request === request) {
          state.items = items;
          state.status = 'ready';
        }
        return items;
      },
      (error: unknown) => {
        if (state.request === request) {
          state.request = undefined;
          state.items = [];
          state.status = 'error';
        }
        throw error;
      },
    );
  state.request = request;
  return request;
}

/** Wait for the standard editor's current loads, without starting or cancelling shared requests. */
export async function settleOptionFieldItems(
  context: OptionContext,
  fieldNames: string[],
  signal: AbortSignal,
  entityAlias?: string,
) {
  const ready = () =>
    fieldNames.every((name) => optionFieldState(context, name, entityAlias).status !== 'loading');
  if (signal.aborted) throw new DOMException('Option loading cancelled', 'AbortError');
  if (ready()) return;
  await new Promise<void>((resolve, reject) => {
    const finish = (error?: Error) => {
      stop();
      clearTimeout(timeout);
      signal.removeEventListener('abort', abort);
      if (error) reject(error);
      else resolve();
    };
    const abort = () => finish(new DOMException('Option loading cancelled', 'AbortError'));
    const stop = watch(
      ready,
      (settled) => {
        if (settled) finish();
      },
      { flush: 'post' },
    );
    const timeout = setTimeout(
      () => finish(new Error('Form option candidates did not finish loading')),
      15000,
    );
    signal.addEventListener('abort', abort, { once: true });
  });
}
