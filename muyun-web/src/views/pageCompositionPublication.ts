import { pagePublicationDigest } from '@muyun/web-core';
import type { OperationReceiptReference } from '@muyun/web-contracts';
import type { HttpClient } from '@muyun/web-core';
import { pageCompositionTransport } from './pageCompositionTransport';

export type PresentationRevision = {
  id?: string;
  version?: number;
  title?: string;
  variantId?: string;
  revisionNo?: number;
  templateAlias?: string;
  templateVersion?: number;
  uiTreeJson?: string;
  status?: 'draft' | 'published' | 'archived';
  enabled?: boolean;
};

/** The shared publication command owns server evidence; editor refresh is a separate concern. */
export function createPageCompositionPublicationCommand(options: {
  http: HttpClient;
  variantId: string;
  revision: PresentationRevision;
  previewBody: unknown;
  composition?: Record<string, unknown>;
  requireCurrent(): void;
  readRevisions(): Promise<PresentationRevision[]>;
}) {
  const candidate: PresentationRevision = JSON.parse(JSON.stringify(options.revision));
  const previewBody: unknown = JSON.parse(JSON.stringify(options.previewBody));
  const composition = options.composition && JSON.parse(JSON.stringify(options.composition));
  const receipt = () => ({ title: '页面已保存并生效', lines: ['业务页面将使用本次确认的配置。'] });
  return {
    async receiptReference(): Promise<OperationReceiptReference | undefined> {
      if (composition) return undefined;
      return {
        kind: 'page-publication',
        variantId: options.variantId,
        revisionId: candidate.id!,
        contentDigest: await pagePublicationDigest(candidate),
      };
    },
    async execute() {
      options.requireCurrent();
      await options.http.request({
        method: 'POST',
        path: pageCompositionTransport.previewRevisionPath(options.variantId, candidate.id!),
        body: previewBody,
      });
      options.requireCurrent();
      let published = candidate;
      if (composition) {
        published = await options.http.request<PresentationRevision>({
          method: 'POST',
          path: `/platform.presentation_publish/revisions/${encodeURIComponent(candidate.id!)}/save-composition`,
          body: { ...composition, revision: candidate },
        });
      } else {
        await options.http.request<number>({
          method: 'POST',
          path: `/platform.presentation_publish/revisions/${encodeURIComponent(candidate.id!)}/publish`,
          body: candidate,
        });
      }
      return { revision: published, receipt: receipt() };
    },
    async lookup() {
      if (composition) {
        const committed = await options.http.request<boolean>({
          method: 'POST',
          path: `/platform.presentation_publish/revisions/${encodeURIComponent(candidate.id!)}/composition-result`,
          body: { ...composition, revision: candidate },
        });
        return committed
          ? { title: '页面保存已确认', lines: ['本次配置已提交；当前页面以最新发布修订为准。'] }
          : undefined;
      }
      const saved = (await options.readRevisions()).find((value) => value.id === candidate.id);
      return (saved?.status === pageCompositionTransport.publishedRevision || saved?.status === 'archived') &&
        saved.templateAlias === candidate.templateAlias &&
        saved.templateVersion === candidate.templateVersion &&
        saved.uiTreeJson === candidate.uiTreeJson
        ? saved.status === 'archived'
          ? { title: '原页面发布已确认', lines: ['本次配置已提交；当前业务页面以最新发布修订为准。'] }
          : receipt()
        : undefined;
    },
  };
}
