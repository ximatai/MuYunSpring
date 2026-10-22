import {
  platformLanguageContext,
  textReferenceMatchesOriginal,
  type LanguageContext,
  type HttpClient,
} from '@muyun/web-core';
import type { TextReference } from '@muyun/web-contracts';
import type { MetadataModelChangeSetProposal } from './metadataModelEditSession';

export type MetadataChangeSetIssue = {
  severity: 'WARNING' | 'ERROR' | string;
  code: string;
  subject: string;
  message: string;
  textSource?: TextReference | null;
  messageArgs?: Record<string, unknown>;
};

/** Translate only explicitly declared, version-matched originals; technical diagnostics stay intact. */
export function metadataChangeSetIssueMessage(
  issue: MetadataChangeSetIssue,
  language: LanguageContext = platformLanguageContext,
): string {
  const source = issue.textSource;
  return source?.sourceKind === 'CODE' && textReferenceMatchesOriginal(source, source.originalText)
    ? language.t(source.sourceId, source.originalText, issue.messageArgs)
    : issue.message;
}

export type MetadataChangeSetPreview = {
  proposalFingerprint: string;
  fieldImpacts: Array<{
    operation: string;
    fieldName: string;
    columnName: string;
    platformManaged: boolean;
    description: string;
  }>;
  schemaImpacts: Array<{
    operation: string;
    schemaName: string;
    tableName: string;
    columnName: string;
    description: string;
  }>;
  orderImpacts: Array<{
    operation: string;
    relationId?: string;
    parentMetadataId?: string;
    orderedIds: string[];
    description: string;
  }>;
  warnings: MetadataChangeSetIssue[];
  errors: MetadataChangeSetIssue[];
};

export function previewMetadataModelChangeSet(
  http: HttpClient,
  moduleAlias: string,
  proposal: MetadataModelChangeSetProposal,
  signal?: AbortSignal,
) {
  return http.request<MetadataChangeSetPreview>({
    method: 'POST',
    path: `/platform.module/${encodeURIComponent(moduleAlias)}/metadata-model/change-set-preview`,
    body: proposal,
    signal,
  });
}

export function applyMetadataModelChangeSet(
  http: HttpClient,
  moduleAlias: string,
  proposal: MetadataModelChangeSetProposal,
  proposalFingerprint: string,
) {
  return http.request({
    method: 'POST',
    path: `/platform.module/${encodeURIComponent(moduleAlias)}/metadata-model/change-set-apply`,
    body: { proposal, proposalFingerprint },
  });
}
