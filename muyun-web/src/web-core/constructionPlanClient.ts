import type {
  ConstructionPlanContent,
  ConstructionTask,
  ConstructionAcceptancePreview,
  ConstructionAcceptanceReceipt,
  ConstructionDeliveryProposal,
  ConstructionDeliveryPreview,
  ConstructionDeliveryReceipt,
  ConstructionProgress,
  ConstructionFieldDescription,
  ConstructionFieldProposal,
  ConstructionFieldPreview,
  ConstructionFieldResult,
  ConstructionInitializationProposal,
  ConstructionInitializationPreview,
  ConstructionInitializationResult,
  ConstructionPlanSnapshot,
  ConstructionPlanSummary,
} from '@muyun/web-contracts';
import type { HttpClient } from './http';
export interface ConstructionPlanClient {
  task(id: string): Promise<ConstructionTask>;
  previewAcceptance(id: string, objectKey: string): Promise<ConstructionAcceptancePreview>;
  confirmAcceptance(
    id: string,
    command: { requestId: string; objectKey: string; fingerprint: string },
  ): Promise<ConstructionAcceptanceReceipt>;
  acceptance(id: string, requestId: string): Promise<ConstructionAcceptanceReceipt | undefined>;
  previewDelivery(id: string, proposal: ConstructionDeliveryProposal): Promise<ConstructionDeliveryPreview>;
  publishDelivery(
    id: string,
    command: { requestId: string; proposal: ConstructionDeliveryProposal; fingerprint: string },
  ): Promise<ConstructionDeliveryReceipt>;
  delivery(id: string, requestId: string): Promise<ConstructionDeliveryReceipt | undefined>;
  progress(id: string, objectKey: string): Promise<ConstructionProgress>;
  describeFields(id: string, objectKey: string): Promise<ConstructionFieldDescription>;
  previewFields(id: string, proposal: ConstructionFieldProposal): Promise<ConstructionFieldPreview>;
  publishFields(
    id: string,
    command: { requestId: string; proposal: ConstructionFieldProposal; fingerprint: string },
  ): Promise<ConstructionFieldResult>;
  fieldChange(id: string, requestId: string): Promise<ConstructionFieldResult | undefined>;
  previewInitialization(
    id: string,
    proposal: ConstructionInitializationProposal,
  ): Promise<ConstructionInitializationPreview>;
  initialize(
    id: string,
    command: { requestId: string; proposal: ConstructionInitializationProposal; fingerprint: string },
  ): Promise<ConstructionInitializationResult>;
  initialization(id: string, objectKey: string): Promise<ConstructionInitializationResult | undefined>;
  list(): Promise<ConstructionPlanSummary[]>;
  read(id: string): Promise<ConstructionPlanSnapshot>;
  history(id: string): Promise<ConstructionPlanSnapshot[]>;
  confirm(
    id: string,
    command: { requestId: string; expectedRevision: number; content: ConstructionPlanContent },
  ): Promise<ConstructionPlanSnapshot>;
  confirmation(id: string, requestId: string): Promise<ConstructionPlanSnapshot | undefined>;
}
export function createConstructionPlanClient(http: HttpClient): ConstructionPlanClient {
  const root = '/platform.application-construction-plans';
  const path = (id: string) => `${root}/${encodeURIComponent(id)}`;
  return {
    task: (id) => http.request({ path: `${path(id)}/task` }),
    previewAcceptance: (id, objectKey) =>
      http.request({ path: `${path(id)}/objects/${encodeURIComponent(objectKey)}/acceptance-preview` }),
    confirmAcceptance: (id, command) =>
      http.request({ path: `${path(id)}/acceptances`, method: 'POST', body: command }),
    acceptance: (id, requestId) =>
      http.request({ path: `${path(id)}/acceptances/${encodeURIComponent(requestId)}` }),
    previewDelivery: (id, proposal) =>
      http.request({ path: `${path(id)}/delivery/preview`, method: 'POST', body: proposal }),
    publishDelivery: (id, command) =>
      http.request({ path: `${path(id)}/delivery`, method: 'POST', body: command }),
    delivery: (id, requestId) =>
      http.request({ path: `${path(id)}/delivery/${encodeURIComponent(requestId)}` }),
    progress: (id, objectKey) =>
      http.request({ path: `${path(id)}/objects/${encodeURIComponent(objectKey)}/progress` }),
    describeFields: (id, objectKey) =>
      http.request({ path: `${path(id)}/objects/${encodeURIComponent(objectKey)}/fields` }),
    previewFields: (id, proposal) =>
      http.request({ path: `${path(id)}/field-changes/preview`, method: 'POST', body: proposal }),
    publishFields: (id, command) =>
      http.request({ path: `${path(id)}/field-changes`, method: 'POST', body: command }),
    fieldChange: (id, requestId) =>
      http.request({ path: `${path(id)}/field-changes/${encodeURIComponent(requestId)}` }),
    previewInitialization: (id, proposal) =>
      http.request({ path: `${path(id)}/initializations/preview`, method: 'POST', body: proposal }),
    initialize: (id, command) =>
      http.request({ path: `${path(id)}/initializations`, method: 'POST', body: command }),
    initialization: (id, objectKey) =>
      http.request({ path: `${path(id)}/initializations/${encodeURIComponent(objectKey)}` }),
    list: () => http.request({ path: root }),
    read: (id) => http.request({ path: path(id) }),
    history: (id) => http.request({ path: `${path(id)}/revisions` }),
    confirm: (id, command) =>
      http.request({ path: `${path(id)}/confirmations`, method: 'POST', body: command }),
    confirmation: (id, requestId) =>
      http.request({ path: `${path(id)}/confirmations/${encodeURIComponent(requestId)}` }),
  };
}
