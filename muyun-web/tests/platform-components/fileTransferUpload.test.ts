import { assert, it } from 'vitest';
import { reactive } from 'vue';
import {
  performBrowserFileTransferUpload,
  unwrapResponsePayload,
} from '@/platform-components/fileTransferUpload.ts';

it('file transfer unwraps the conventional storage response envelope without coupling to a storage DTO', () => {
  const payload = { fileId: 'file-1', sizeBytes: 1024 };
  assert.equal(unwrapResponsePayload({ success: true, data: payload }), payload);
  assert.deepEqual(unwrapResponsePayload({ fileId: 'file-1' }), { fileId: 'file-1' });
  assert.equal(unwrapResponsePayload(undefined), undefined);
});

it('file transfer lifecycle updates the rendered item through completed after storage and business confirmation', async () => {
  const originalXmlHttpRequest = globalThis.XMLHttpRequest;
  class SuccessfulUploadRequest {
    status = 201;
    responseText = JSON.stringify({ success: true, data: { items: [{ id: 'file-1' }] } });
    upload: { onprogress?: (event: ProgressEvent<EventTarget>) => void } = {};
    onload?: () => void;
    onerror?: () => void;
    onabort?: () => void;

    open() {}
    setRequestHeader() {}
    send() {
      this.upload.onprogress?.({ lengthComputable: true, loaded: 4, total: 4 } as ProgressEvent<EventTarget>);
      queueMicrotask(() => this.onload?.());
    }
    abort() {
      this.onabort?.();
    }
  }
  Object.defineProperty(globalThis, 'XMLHttpRequest', {
    configurable: true,
    value: SuccessfulUploadRequest,
  });
  try {
    const item = reactive({ state: 'ready', progress: 0 });
    const transitions: string[] = [];
    const file = Object.assign(new Blob(['demo'], { type: 'image/jpeg' }), { name: 'demo.jpg' }) as File;
    const { result } = await performBrowserFileTransferUpload(
      file,
      async () => ({ uploadUrl: 'http://files.example/upload' }),
      async (receipt) => ({ fileId: (receipt.payload as { items: Array<{ id: string }> }).items[0].id }),
      {
        stateChanged: (state) => {
          item.state = state;
          transitions.push(state);
        },
        progressChanged: (percent) => {
          item.progress = percent;
        },
      },
    );

    assert.deepEqual(transitions, ['requesting', 'uploading', 'confirming', 'completed']);
    assert.equal(item.state, 'completed');
    assert.equal(item.progress, 100);
    assert.deepEqual(result, { fileId: 'file-1' });
  } finally {
    Object.defineProperty(globalThis, 'XMLHttpRequest', {
      configurable: true,
      value: originalXmlHttpRequest,
    });
  }
});
