import {
  createSourceReferencePickerProvider,
  sourceReferencePickerReloadKey,
  type RecordFormFieldPickerConfig,
  type RecordPickerRecord,
  type ReferencePickerProvider,
} from '@muyun/platform-components';
import type { ResolvedReferenceFieldDescriptor } from '@muyun/web-contracts';
import type { ReferenceResolveClient } from '@muyun/web-core';

type SourceReferencePickerConfigOptions = {
  providerScopeKey: string;
  sourceModuleAlias: string;
  reference: ResolvedReferenceFieldDescriptor;
  pickerFieldName: string;
  referenceResolver: () => ReferenceResolveClient;
  formValues: () => Record<string, unknown>;
  reloadRecord: () => Record<string, unknown> | undefined;
  source: () => { recordId: string } | undefined;
  /** Scoped-tree hosts retain their compact legacy picker alongside lazy expansion. */
  legacyTreeLoaders?: boolean;
};

type SourceReferencePickerConfig = Pick<
  RecordFormFieldPickerConfig,
  'provider' | 'reloadKey' | 'loadOptions' | 'loadTree' | 'resolveOptions'
>;

/**
 * Retains source-authorized providers for one page-runtime instance. The source callbacks stay
 * live so page and child-row drafts can change without recreating the picker or widening its
 * candidate authority. SOURCE_FIELD TREE delivery is part of the same provider contract; it is
 * never reconstructed from a paged QUERY result.
 */
export function createSourceReferencePickerConfigAssembler() {
  const providers = new Map<
    string,
    {
      provider: ReferencePickerProvider;
      live: { options: SourceReferencePickerConfigOptions };
    }
  >();

  return (options: SourceReferencePickerConfigOptions): SourceReferencePickerConfig => {
    const { providerScopeKey, sourceModuleAlias, reference, pickerFieldName, reloadRecord } = options;
    const referenceContractKey = JSON.stringify({
      targetModuleAlias: reference.targetModuleAlias,
      cardinality: reference.cardinality,
      pickerMode: reference.pickerMode,
      resolvePath: reference.resolvePath,
      candidateDependencies: reference.candidateDependencies,
    });
    const providerKey = `${providerScopeKey}:${sourceModuleAlias}:${pickerFieldName}:${referenceContractKey}`;
    let entry = providers.get(providerKey);
    if (!entry) {
      const live = { options };
      const provider = createSourceReferencePickerProvider({
        sourceModuleAlias,
        fieldName: pickerFieldName,
        reference,
        resolver: () => live.options.referenceResolver(),
        formValues: () => live.options.formValues(),
        source: () => live.options.source(),
      });
      entry = { provider, live };
      providers.set(providerKey, entry);
    }
    entry.live.options = options;
    const config: SourceReferencePickerConfig = {
      provider: entry.provider,
      reloadKey: sourceReferencePickerReloadKey(
        sourceModuleAlias,
        pickerFieldName,
        reference,
        reloadRecord(),
      ),
    };
    if (reference.pickerMode !== 'TREE' || !options.legacyTreeLoaders) return config;

    const pickerRecord = (item: {
      id: string;
      title?: string;
      projections?: Record<string, unknown>;
      affectPatch?: Record<string, unknown>;
    }): RecordPickerRecord => ({
      id: item.id,
      title: item.title,
      ...(item.projections ?? {}),
      projections: item.projections,
      affectPatch: item.affectPatch,
    });
    return {
      ...config,
      loadOptions: async (keyword) => {
        const response = await entry.live.options.referenceResolver().resolve(pickerFieldName, {
          mode: 'QUERY',
          fuzzy: keyword || undefined,
          page: { pageNum: 1, pageSize: 50 },
          formValues: entry.live.options.formValues(),
          source: entry.live.options.source(),
        });
        return response.options.map(pickerRecord);
      },
      loadTree: async () => {
        const response = await entry.live.options.referenceResolver().resolve(pickerFieldName, {
          mode: 'TREE',
          formValues: entry.live.options.formValues(),
          source: entry.live.options.source(),
        });
        return response.tree ?? [];
      },
      resolveOptions: async (values) => {
        const response = await entry.live.options.referenceResolver().resolve(pickerFieldName, {
          mode: 'TRANSLATE',
          values,
          formValues: entry.live.options.formValues(),
          source: entry.live.options.source(),
        });
        return response.results.flatMap((result) => (result.item ? [pickerRecord(result.item)] : []));
      },
    };
  };
}
