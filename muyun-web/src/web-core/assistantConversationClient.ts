import type { HttpClient } from './http';
import type { AssistantConversationMessage } from '@muyun/web-contracts';

export interface AssistantConversationContent {
  title: string;
  messages: Array<{ role: 'user' | 'assistant' | 'status'; text: string }>;
  history: AssistantConversationMessage[];
  planId?: string;
  pendingRequest?: string;
}
export interface AssistantConversationSnapshot {
  id: string;
  revision: number;
  updatedAt: string;
  content: AssistantConversationContent;
}
export interface AssistantConversationSummary {
  id: string;
  title: string;
  updatedAt: string;
}
export interface AssistantConversationClient {
  list(scopeKey: string, page: number): Promise<AssistantConversationSummary[]>;
  read(id: string, scopeKey: string): Promise<AssistantConversationSnapshot>;
  save(
    id: string,
    scopeKey: string,
    expectedRevision: number,
    content: AssistantConversationContent,
  ): Promise<AssistantConversationSnapshot>;
}
export function createAssistantConversationClient(http: HttpClient): AssistantConversationClient {
  const path = '/platform.assistant-conversations';
  return {
    list: (scopeKey, page) => http.request({ path, query: { scopeKey, page } }),
    read: (id, scopeKey) => http.request({ path: `${path}/${encodeURIComponent(id)}`, query: { scopeKey } }),
    save: (id, scopeKey, expectedRevision, content) =>
      http.request({
        path: `${path}/${encodeURIComponent(id)}`,
        method: 'PUT',
        query: { scopeKey },
        body: { expectedRevision, content },
      }),
  };
}
