import { assert, expect, it } from 'vitest';
import type { HttpClient, HttpRequestOptions } from '../../src/web-core/http.ts';
import {
  acceptedMediaTypes,
  appendUploadedFileReference,
  fileReferenceUploadIntent,
  fileReferenceIds,
  fileReferenceDraftRecordId,
  issueFileReferenceUploadAccess,
  replacedFileReferenceId,
  registerFileReferenceUpload,
  uploadInlineFileReference,
  uploadedFileId,
} from '@/platform-components/fileReferenceTransfer.ts';
import type { ResolvedFileReferenceFieldDescriptor } from '../../src/web-contracts/index.ts';

const single: ResolvedFileReferenceFieldDescriptor = {
  fieldRef: { fieldName: 'fileId' },
  allowedMediaTypes: ['application/pdf'],
  maxFileSizeBytes: 1024,
  maxFiles: 1,
  storagePolicy: 'MUYUN_FILE_SERVER',
  uploadAvailable: true,
  readAvailable: true,
};

it('file-reference upload tickets use the current module standard endpoint', async () => {
  const requests: unknown[] = [];
  const http: HttpClient = {
    request: async <T>(request: HttpRequestOptions) => {
      requests.push(request);
      return { url: 'https://files.example/upload', registrationId: 'registration-1' } as T;
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
      registrationId: 'registration-1',
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

it('requires a platform registration before using a FileServer upload ticket', async () => {
  const http: HttpClient = {
    request: async <T>() => ({ url: 'https://files.example/upload' }) as T,
  };
  await expect(
    issueFileReferenceUploadAccess(
      http,
      'sales.contract',
      single,
      {},
      new File(['pdf'], 'guide.pdf'),
      'CREATE',
    ),
  ).rejects.toThrow('上传登记标识');
});

it('registers only the server-issued ticket and file identifier after direct upload', async () => {
  const requests: HttpRequestOptions[] = [];
  const http: HttpClient = {
    request: async <T>(request: HttpRequestOptions) => {
      requests.push(request);
      return undefined as T;
    },
  };
  await registerFileReferenceUpload(http, 'sales.contract', 'registration-1', 'file-1');
  assert.deepEqual(requests, [
    {
      method: 'POST',
      path: '/sales.contract/file-transfer/upload-registration',
      body: { ticketId: 'registration-1', fileId: 'file-1' },
    },
  ]);
});

it('keeps DATABASE_INLINE content on the authenticated platform upload transport', async () => {
  let observed: HttpRequestOptions | undefined;
  const http: HttpClient = {
    request: async <T>(request: HttpRequestOptions) => {
      observed = request;
      return { items: [{ id: 'asset-1' }] } as T;
    },
  };
  const result = await uploadInlineFileReference(
    http,
    'iam.tenant',
    { ...single, storagePolicy: 'DATABASE_INLINE' },
    { id: 'tenant-1' },
    new File(['pdf'], 'guide.pdf'),
    'CREATE',
  );
  assert.equal(observed?.path, '/iam.tenant/file-transfer/inline-upload');
  assert.ok(observed?.body instanceof FormData);
  assert.equal(uploadedFileId(result.payload), 'asset-1');
});

it('preallocates one stable entity ID shared by uploads from the same new draft', () => {
  const draft = { title: 'new document' };
  const id = fileReferenceDraftRecordId(draft);
  assert.match(id, /^[a-f0-9]{32}$/);
  assert.equal(fileReferenceDraftRecordId(draft), id);
  assert.notEqual(fileReferenceDraftRecordId({}), id);
  assert.equal(fileReferenceDraftRecordId({ ...draft, id }), id);
  assert.deepEqual(draft, { title: 'new document' });
});
