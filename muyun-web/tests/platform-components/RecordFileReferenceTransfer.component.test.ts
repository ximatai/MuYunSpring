import { mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import type { ModuleContext } from '@/web-core';
import type { ResolvedFileReferenceFieldDescriptor } from '@/web-contracts';
import FileTransferUploader from '@/platform-components/FileTransferUploader.vue';
import RecordFileReferenceTransfer from '@/platform-components/RecordFileReferenceTransfer.vue';

const singleInlineImage: ResolvedFileReferenceFieldDescriptor = {
  fieldRef: { fieldName: 'logoAssetId' },
  allowedMediaTypes: ['image/png'],
  maxFileSizeBytes: 512 * 1024,
  maxFiles: 1,
  storagePolicy: 'DATABASE_INLINE',
  uploadAvailable: true,
  readAvailable: true,
};

it('releases a completed single-image upload after binding so replacement remains available', async () => {
  const wrapper = mount(RecordFileReferenceTransfer, {
    props: {
      value: 'asset-old',
      record: { alias: 'tenant-a' },
      context: { moduleAlias: 'iam.tenant' } as ModuleContext<unknown>,
      definition: singleInlineImage,
      releaseCompletedUploadOnBind: true,
    },
  });
  const uploader = wrapper.findComponent(FileTransferUploader);

  uploader.vm.$emit('completed', {
    payload: { items: [{ id: 'asset-new' }] },
  });
  await wrapper.vm.$nextTick();

  expect(wrapper.emitted('update:value')).toEqual([['asset-new']]);
  expect(uploader.props('releasedCompletedFileIds')).toEqual(['asset-new']);
});

it.each([true, false])(
  'binds a direct FileServer upload only after registration succeeds (%s)',
  async (accepted) => {
    const requests: Array<{ path: string; body?: unknown }> = [];
    let resolveRegistration: (() => void) | undefined;
    let rejectRegistration: ((cause: Error) => void) | undefined;
    class UploadedFileRequest {
      status = 201;
      responseText = JSON.stringify({ data: { items: [{ id: 'file-new' }] } });
      upload = {};
      onload?: () => void;
      open(method: string, url: string) {
        expect(method).toBe('POST');
        expect(url).toBe('https://files.example/upload');
      }
      send(body: FormData) {
        expect(body.get('files')).toBeInstanceOf(File);
        queueMicrotask(() => this.onload?.());
      }
    }
    vi.stubGlobal('XMLHttpRequest', UploadedFileRequest);
    const wrapper = mount(RecordFileReferenceTransfer, {
      props: {
        value: undefined,
        record: { id: 'draft-1' },
        context: {
          moduleAlias: 'sales.document',
          http: {
            request: async (request: { path: string; body?: unknown }) => {
              requests.push(request);
              if (request.path.endsWith('/upload-ticket')) {
                return { url: 'https://files.example/upload', registrationId: 'ticket-1' };
              }
              return new Promise<void>((resolve, reject) => {
                resolveRegistration = resolve;
                rejectRegistration = reject;
              });
            },
          },
        } as ModuleContext<unknown>,
        definition: {
          ...singleInlineImage,
          fieldRef: { fieldName: 'fileId' },
          storagePolicy: 'MUYUN_FILE_SERVER',
        },
      },
    });
    try {
      const input = wrapper.get('input[type="file"]');
      Object.defineProperty(input.element, 'files', {
        configurable: true,
        value: [new File(['image'], 'image.png', { type: 'image/png' })],
      });
      await input.trigger('change');
      await vi.waitFor(() => expect(requests).toHaveLength(2));
      expect(requests[1]).toEqual({
        method: 'POST',
        path: '/sales.document/file-transfer/upload-registration',
        body: { ticketId: 'ticket-1', fileId: 'file-new' },
      });
      expect(wrapper.emitted('update:value')).toBeUndefined();
      if (accepted) {
        resolveRegistration?.();
        await vi.waitFor(() => expect(wrapper.emitted('update:value')).toEqual([['file-new']]));
      } else {
        rejectRegistration?.(new Error('upload registration denied'));
        await vi.waitFor(() => expect(wrapper.text()).toContain('upload registration denied'));
        expect(wrapper.emitted('update:value')).toBeUndefined();
      }
    } finally {
      wrapper.unmount();
      vi.unstubAllGlobals();
    }
  },
);
