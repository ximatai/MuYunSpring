export interface SseEvent {
  event: string;
  data: string;
}

/** Incrementally decodes the SSE framing shared by authenticated platform streams. */
export function createSseParser(emit: (event: SseEvent) => void) {
  let buffer = '';
  let eventName = 'message';
  let dataLines: string[] = [];

  function dispatch() {
    if (dataLines.length > 0) emit({ event: eventName, data: dataLines.join('\n') });
    eventName = 'message';
    dataLines = [];
  }

  function processLine(line: string) {
    if (line === '') {
      dispatch();
      return;
    }
    if (line.startsWith(':')) return;
    const separator = line.indexOf(':');
    const field = separator >= 0 ? line.slice(0, separator) : line;
    const value = separator >= 0 ? line.slice(separator + 1).replace(/^ /, '') : '';
    if (field === 'event') eventName = value || 'message';
    else if (field === 'data') dataLines.push(value);
  }

  return {
    push(chunk: string) {
      buffer += chunk;
      let lineEnd = buffer.indexOf('\n');
      while (lineEnd >= 0) {
        processLine(buffer.slice(0, lineEnd).replace(/\r$/, ''));
        buffer = buffer.slice(lineEnd + 1);
        lineEnd = buffer.indexOf('\n');
      }
    },
    finish() {
      if (buffer) {
        processLine(buffer.replace(/\r$/, ''));
        buffer = '';
      }
      dispatch();
    },
  };
}
