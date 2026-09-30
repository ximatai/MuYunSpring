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
      const saved = (await options.readRevisions()).find((value) => value.id === candidate.id);
      return saved?.status === pageCompositionTransport.publishedRevision &&
        saved.uiTreeJson === candidate.uiTreeJson
        ? receipt()
        : undefined;
    },
  };
}
