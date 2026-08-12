import { assert, it } from 'vitest';
import type { HttpClient } from '../src/web-core/http.ts';
import {
  acceptedMediaTypes,
  appendUploadedFileReference,
  fileReferenceUploadIntent,
  fileReferenceIds,
  issueFileReferenceUploadAccess,
  replacedFileReferenceId,
  uploadedFileId,
} from '@/platform-components/fileReferenceTransfer.ts';
import type { ResolvedFileReferenceFieldDescriptor } from '../src/web-contracts/index.ts';

const single: ResolvedFileReferenceFieldDescriptor = {
  fieldRef: { fieldName: 'fileId' },
  allowedMediaTypes: ['application/pdf'],
  maxFileSizeBytes: 1024,
  maxFiles: 1,
  storagePolicy: 'MUYUN_FILE_SERVER',
  uploadAvailable: true,
};

it('file-reference upload tickets use the current module standard endpoint', async () => {
  const requests: unknown[] = [];
  const http: HttpClient = {
    request: async (request) => {
      requests.push(request);
      return { url: 'https://files.example/upload' };
    },
  };

  assert.deepEqual(
    await issueFileReferenceUploadAccess(
      http,
      'mr.knowledge_file',
      single,
      { directoryId: 'directory-1' },
      new File(['pdf'], 'guide.pdf', { type: 'application/pdf' }),
      'CREATE',
    ),
    {
      uploadUrl: 'https://files.example/upload',
    },
  );
  assert.deepEqual(requests, [
    {
      method: 'POST',
      path: '/mr.knowledge_file/file-transfer/upload-ticket',
      body: {
        relationCode: undefined,
        fieldName: 'fileId',
        draft: { directoryId: 'directory-1' },
        file: { name: 'guide.pdf', mediaType: 'application/pdf', sizeBytes: 3 },
        intent: 'CREATE',
      },
    },
  ]);
});

it('file-reference upload results bind only the FileServer returned identifier', () => {
  assert.equal(uploadedFileId({ items: [{ id: 'file-1' }] }), 'file-1');
  assert.throws(() => uploadedFileId({ items: [] }), /唯一文件标识/);
  assert.throws(() => uploadedFileId({ items: [{ id: '' }] }), /文件标识/);
});

it('file-reference draft values respect single and multi-file declarations', () => {
  assert.equal(appendUploadedFileReference('file-old', 'file-new', single), 'file-new');
  const multiple = { ...single, maxFiles: 2 };
  assert.deepEqual(appendUploadedFileReference(['file-1'], 'file-2', multiple), ['file-1', 'file-2']);
  assert.deepEqual(appendUploadedFileReference(['file-1'], 'file-1', multiple), ['file-1']);
  assert.throws(() => appendUploadedFileReference(['file-1', 'file-2'], 'file-3', multiple), /最多允许/);
  assert.equal(acceptedMediaTypes(single), 'application/pdf');
  assert.equal(acceptedMediaTypes({ ...single, allowedMediaTypes: [] }), undefined);
  assert.deepEqual(fileReferenceIds('file-1'), ['file-1']);
  assert.deepEqual(fileReferenceIds(['file-1', '', 1]), ['file-1']);
});

it('file-reference upload intent distinguishes creation, append and replacement', () => {
  const multiple = { ...single, maxFiles: 2 };
  assert.equal(fileReferenceUploadIntent(undefined, single), 'CREATE');
  assert.equal(fileReferenceUploadIntent('file-old', single), 'REPLACE');
  assert.equal(fileReferenceUploadIntent(['file-1'], multiple), 'APPEND');
  assert.equal(replacedFileReferenceId('file-old', 'file-new', single), 'file-old');
  assert.equal(replacedFileReferenceId('file-old', 'file-old', single), undefined);
  assert.equal(replacedFileReferenceId(['file-1'], 'file-2', multiple), undefined);
});
