export interface RuntimeLogSseEvent {
  event: string;
  data: string;
}

/**
 * Incrementally decodes an SSE response body. Keeping this small parser beside
 * the log surface avoids coupling a one-way operational stream to the broader
 * realtime messaging runtime.
 */
export function createRuntimeLogSseParser(emit: (event: RuntimeLogSseEvent) => void) {
  let buffer = '';
  let eventName = 'message';
  let dataLines: string[] = [];

  function dispatch() {
    if (dataLines.length > 0) {
      emit({ event: eventName, data: dataLines.join('\n') });
    }
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
    if (field === 'event') {
      eventName = value || 'message';
    } else if (field === 'data') {
      dataLines.push(value);
    }
  }

  return {
    push(chunk: string) {
      buffer += chunk;
      let lineEnd = buffer.indexOf('\n');
      while (lineEnd >= 0) {
        const line = buffer.slice(0, lineEnd).replace(/\r$/, '');
        buffer = buffer.slice(lineEnd + 1);
        processLine(line);
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
