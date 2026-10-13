import { computed, inject, provide, ref, type ComputedRef, type InjectionKey } from 'vue';
import packs from './locales/languagePacks.json';
import { withHttpHeaders, type HttpClient, type StreamingHttpClient } from './http';

export interface LanguageDefinition {
  readonly tag: string;
  readonly nativeName: string;
  readonly fallbackTag?: string | null;
  readonly direction: 'ltr' | 'rtl';
  readonly enabled?: boolean;
}
export interface LanguageCatalog {
  readonly defaultTag: string;
  languages: readonly LanguageDefinition[];
}
export interface TextResolution {
  text: string;
  requestedTag: string;
  resourceTag?: string;
  missing: boolean;
  fallback: boolean;
}
export interface LanguageContext {
  readonly catalog: ComputedRef<LanguageCatalog>;
  readonly languageTag: ComputedRef<string>;
  readonly requestedLanguageTag: ComputedRef<string | undefined>;
  readonly revision: ComputedRef<number>;
  readonly selectionRevision: ComputedRef<number>;
  readonly fallbackLanguageTags: ComputedRef<readonly string[]>;
  readonly language: ComputedRef<LanguageDefinition>;
  setLanguage(tag?: string): void;
  setCatalog(catalog: LanguageCatalog): void;
  registerResources(tag: string, messages: Record<string, string>): void;
  resolve(key: string, defaultText?: string, args?: Record<string, unknown>): TextResolution;
  t(key: string, defaultText?: string, args?: Record<string, unknown>): string;
}

const initialCatalog: LanguageCatalog = {
  defaultTag: 'zh-CN',
  languages: Object.entries(packs).map(([tag, pack]) => ({
    tag,
    nativeName: pack.nativeName,
    direction: pack.direction as 'ltr' | 'rtl',
    enabled: 'enabled' in pack ? (pack.enabled as boolean) : true,
    fallbackTag: 'fallbackTag' in pack ? pack.fallbackTag : undefined,
  })),
};

export function createLanguageContext(
  options: {
    catalog?: LanguageCatalog;
    languageTag?: string;
    resources?: Record<string, Record<string, string>>;
  } = {},
): LanguageContext {
  const catalogState = ref(validateCatalog(options.catalog ?? initialCatalog));
  const selectionIntent = ref(options.languageTag);
  const active = ref(selectLanguage(catalogState.value, selectionIntent.value));
  const revisionState = ref(0);
  const selectionRevision = ref(0);
  const resources = new Map<string, Record<string, string>>(
    Object.entries(packs).map(([tag, pack]) => [canonicalTag(tag), { ...pack.messages }]),
  );
  const addResources = (tag: string, messages: Record<string, string>) => {
    const canonical = canonicalTag(tag);
    if (!catalogState.value.languages.some((entry) => entry.tag === canonical))
      throw new Error('Unregistered resource language');
    const previous = resources.get(canonical) ?? {};
    for (const [key, value] of Object.entries(messages))
      if (Object.hasOwn(previous, key) && previous[key] !== value)
        throw new Error(`Conflicting language resource: ${canonical}/${key}`);
    resources.set(canonical, { ...previous, ...messages });
  };
  Object.entries(options.resources ?? {}).forEach(([tag, messages]) => addResources(tag, messages));
  const catalog = computed(() => catalogState.value);
  const languageTag = computed(() => active.value);
  const language = computed(() => catalog.value.languages.find((entry) => entry.tag === active.value)!);
  const resolve: LanguageContext['resolve'] = (key, defaultText, args) => {
    // Track resource publication as well as selection in Vue consumers of t().
    void revisionState.value;
    for (const tag of fallbackTags(catalog.value, active.value)) {
      const messages = resources.get(tag);
      if (messages && Object.hasOwn(messages, key))
        return {
          text: interpolate(messages[key]!, args),
          requestedTag: active.value,
          resourceTag: tag,
          missing: false,
          fallback: tag !== active.value,
        };
    }
    return {
      text: interpolate(defaultText ?? key, args),
      requestedTag: active.value,
      missing: true,
      fallback: true,
    };
  };
  return {
    catalog,
    languageTag,
    requestedLanguageTag: computed(() => selectionIntent.value),
    language,
    fallbackLanguageTags: computed(() => fallbackTags(catalog.value, active.value)),
    revision: computed(() => revisionState.value),
    selectionRevision: computed(() => selectionRevision.value),
    resolve,
    t: (...args) => resolve(...args).text,
    setLanguage(tag) {
      selectionRevision.value++;
      selectionIntent.value = tag;
      const selected = selectLanguage(catalog.value, tag);
      if (selected !== active.value) {
        active.value = selected;
        revisionState.value++;
      }
    },
    setCatalog(value) {
      catalogState.value = validateCatalog(value);
      active.value = selectLanguage(catalog.value, selectionIntent.value);
      revisionState.value++;
    },
    registerResources(tag, messages) {
      addResources(tag, messages);
      revisionState.value++;
    },
  };
}

function canonicalTag(tag: string) {
  return Intl.getCanonicalLocales(tag)[0]!;
}
function selectLanguage(catalog: LanguageCatalog, tag?: string) {
  if (!tag) return catalog.defaultTag;
  try {
    const canonical = canonicalTag(tag);
    let candidate = canonical;
    while (candidate) {
      const found = catalog.languages.find((entry) => entry.enabled !== false && entry.tag === candidate);
      if (found) return found.tag;
      candidate = candidate.includes('-') ? candidate.slice(0, candidate.lastIndexOf('-')) : '';
    }
  } catch {
    /* Invalid or unsupported user preference falls back to the deployment default. */
  }
  return catalog.defaultTag;
}
function validateCatalog(value: LanguageCatalog): LanguageCatalog {
  if (
    value.languages.some(
      (language) => language.enabled !== undefined && typeof language.enabled !== 'boolean',
    )
  )
    throw new Error('Invalid language enabled flag');
  const catalog = {
    defaultTag: canonicalTag(value.defaultTag),
    languages: value.languages.map((language) => ({
      tag: canonicalTag(language.tag),
      nativeName: language.nativeName,
      enabled: language.enabled !== false,
      direction: language.direction,
      ...(language.fallbackTag ? { fallbackTag: canonicalTag(language.fallbackTag) } : {}),
    })),
  };
  if (
    !catalog.languages.some((entry) => entry.tag === catalog.defaultTag && entry.enabled !== false) ||
    new Set(catalog.languages.map((entry) => entry.tag)).size !== catalog.languages.length ||
    catalog.languages.some((entry) => !entry.nativeName?.trim() || !['ltr', 'rtl'].includes(entry.direction))
  )
    throw new Error('Invalid language catalog');
  for (const language of catalog.languages) fallbackTags(catalog, language.tag);
  catalog.languages.forEach(Object.freeze);
  Object.freeze(catalog.languages);
  return Object.freeze(catalog);
}
function fallbackTags(catalog: LanguageCatalog, tag: string) {
  const chain = new Set<string>();
  let current: string | undefined | null = tag;
  while (current) {
    if (chain.has(current)) throw new Error('Language fallback cycle');
    chain.add(current);
    const language = catalog.languages.find((entry) => entry.tag === current);
    if (!language) throw new Error('Unregistered fallback language');
    current = language.fallbackTag;
  }
  chain.add(catalog.defaultTag);
  return [...chain];
}
function interpolate(text: string, args?: Record<string, unknown>) {
  return text.replace(/\{([A-Za-z][A-Za-z0-9_.-]*)\}/g, (match, key: string) =>
    args && Object.hasOwn(args, key) ? String(args[key] ?? '') : match,
  );
}

export const platformLanguageContext = createLanguageContext();
const LANGUAGE_CONTEXT: InjectionKey<LanguageContext> = Symbol('MuYunLanguageContext');
export function provideLanguageContext(context: LanguageContext) {
  provide(LANGUAGE_CONTEXT, context);
}
export function useLanguageContext() {
  return inject(LANGUAGE_CONTEXT, platformLanguageContext);
}
export function withLanguageContext(
  client: StreamingHttpClient,
  context: LanguageContext,
): StreamingHttpClient;
export function withLanguageContext(client: HttpClient, context: LanguageContext): HttpClient;
export function withLanguageContext(client: HttpClient, context: LanguageContext): HttpClient {
  return withHttpHeaders(client, () => ({ 'Accept-Language': context.languageTag.value }));
}
export async function loadLanguageCatalog(http: HttpClient, context: LanguageContext) {
  const catalog = await http.request<LanguageCatalog>({ path: '/platform.localization/catalog' });
  context.setCatalog(catalog);
}
