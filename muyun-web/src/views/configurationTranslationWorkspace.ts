import { computed, ref } from 'vue';
import { actionResultData, type HttpClient } from '@muyun/web-core';
import type { TextReference } from '@muyun/web-contracts';

export interface ConfigurationTranslation {
  id: string;
  version: number;
  moduleAlias: string;
  sourceId: string;
  sourceVersion: string;
  originalText: string;
  languageTag: string;
  draftText: string;
  publishedText?: string;
  publishedRevision: number;
}

/** Translation working copies are separate from source configuration and interface language. */
export function createConfigurationTranslationWorkspace(http: HttpClient) {
  const moduleAlias = ref('');
  const loadedModuleAlias = ref('');
  const ready = computed(() => loadedModuleAlias.value === moduleAlias.value.trim());
  const sourceId = ref<string>();
  const targetLanguage = ref('en');
  const sources = ref<TextReference[]>([]);
  const translations = ref<ConfigurationTranslation[]>([]);
  const drafts = ref<Record<string, string>>({});
  const draftVersions = new Map<string, number | undefined>();
  const busy = ref(false);
  const source = computed(() => sources.value.find((item) => item.sourceId === sourceId.value));
  const key = computed(() =>
    JSON.stringify([
      loadedModuleAlias.value,
      source.value?.sourceId,
      source.value?.sourceVersion,
      targetLanguage.value,
    ]),
  );
  const translation = computed(() =>
    translations.value.find(
      (row) =>
        row.sourceId === source.value?.sourceId &&
        row.sourceVersion === source.value?.sourceVersion &&
        row.languageTag === targetLanguage.value,
    ),
  );
  const previous = computed(() =>
    translations.value.find(
      (row) =>
        row.sourceId === source.value?.sourceId &&
        row.sourceVersion !== source.value?.sourceVersion &&
        row.languageTag === targetLanguage.value,
    ),
  );
  const text = computed({
    get: () => drafts.value[key.value] ?? translation.value?.draftText ?? '',
    set: (value: string) => {
      const next = { ...drafts.value };
      if (value === (translation.value?.draftText ?? '')) {
        delete next[key.value];
        draftVersions.delete(key.value);
      } else {
        if (!(key.value in next)) draftVersions.set(key.value, translation.value?.version);
        next[key.value] = value;
      }
      drafts.value = next;
    },
  });
  const dirty = computed(() => text.value !== (translation.value?.draftText ?? ''));
  let loadRevision = 0;
  const retain = (row: ConfigurationTranslation) => {
    translations.value = [...translations.value.filter((item) => item.id !== row.id), row];
  };

  async function load() {
    if (busy.value || !moduleAlias.value.trim()) return;
    const revision = ++loadRevision;
    const alias = moduleAlias.value.trim();
    busy.value = true;
    try {
      const [originals, rows] = await Promise.all([
        http.request<TextReference[]>({
          path: `/platform.localization/modules/${encodeURIComponent(alias)}/sources`,
        }),
        http.request<ConfigurationTranslation[]>({
          path: `/platform.localization/modules/${encodeURIComponent(alias)}/translations`,
        }),
      ]);
      if (revision !== loadRevision || moduleAlias.value.trim() !== alias) return;
      loadedModuleAlias.value = alias;
      sources.value = originals;
      translations.value = rows;
      if (!originals.some((item) => item.sourceId === sourceId.value))
        sourceId.value = originals[0]?.sourceId;
    } finally {
      if (revision === loadRevision) busy.value = false;
    }
  }
  async function save() {
    if (busy.value || !ready.value || !source.value || !text.value.trim()) return;
    const currentSource = source.value;
    const currentKey = key.value;
    const value = text.value;
    busy.value = true;
    try {
      const row = actionResultData(
        await http.request<ConfigurationTranslation>({
          method: 'POST',
          path: '/platform.localization/drafts',
          body: {
            moduleAlias: loadedModuleAlias.value,
            sourceId: currentSource.sourceId,
            sourceVersion: currentSource.sourceVersion,
            languageTag: targetLanguage.value,
            text: value,
            version: currentKey in drafts.value ? draftVersions.get(currentKey) : translation.value?.version,
          },
        }),
      );
      retain(row);
      const next = { ...drafts.value };
      // A consumer may continue editing during the request; keep only that newer edit.
      if (next[currentKey] === value) {
        delete next[currentKey];
        draftVersions.delete(currentKey);
      } else if (currentKey in next) draftVersions.set(currentKey, row.version);
      drafts.value = next;
    } finally {
      busy.value = false;
    }
  }
  async function publish() {
    if (busy.value || !ready.value || dirty.value || !translation.value || !source.value) return;
    const row = translation.value;
    busy.value = true;
    try {
      retain(
        actionResultData(
          await http.request<ConfigurationTranslation>({
            method: 'POST',
            path: `/platform.localization/translations/${encodeURIComponent(row.id)}/publish`,
            body: { sourceVersion: source.value.sourceVersion, version: row.version },
          }),
        ),
      );
    } finally {
      busy.value = false;
    }
  }
  return {
    moduleAlias,
    loadedModuleAlias,
    ready,
    sourceId,
    targetLanguage,
    sources,
    source,
    translation,
    previous,
    text,
    dirty,
    busy,
    load,
    save,
    publish,
  };
}
