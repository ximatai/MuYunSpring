import { createSseParser, type SseEvent } from '@muyun/web-core';

export type RuntimeLogSseEvent = SseEvent;
export const createRuntimeLogSseParser = createSseParser;
