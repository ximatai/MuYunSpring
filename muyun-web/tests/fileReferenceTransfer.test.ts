import test from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient } from '../src/web-core/http.ts';
import {
  acceptedMediaTypes,
  appendUploadedFileReference,
  FileReferenceFormSaveSession,
  fileReferenceUploadIntent,
  fileReferenceRecordPath,
  fileReferenceIds,
  issueFileReferenceUploadAccess,
  replacedFileReferenceId,
  uploadedFileId,
} from '../src/platform-components/fileReferenceTransfer.ts';
import type { ResolvedFileReferenceFieldDescriptor } from '../src/web-contracts/index.ts';

const single: ResolvedFileReferenceFieldDescriptor = {
  fieldRef: { fieldName: 'fileId' },
  allowedMediaTypes: ['application/pdf'],
  maxFileSizeBytes: 1024,
  maxFiles: 1,
  uploadAvailable: true,
};

test('file-reference upload tickets use the current module standard endpoint', async () => {
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

test('file-reference upload results bind only the FileServer returned identifier', () => {
  assert.equal(uploadedFileId({ items: [{ id: 'file-1' }] }), 'file-1');
  assert.throws(() => uploadedFileId({ items: [] }), /唯一文件标识/);
  assert.throws(() => uploadedFileId({ items: [{ id: '' }] }), /文件标识/);
});

test('file-reference draft values respect single and multi-file declarations', () => {
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

test('file-reference upload intent distinguishes creation, append and replacement', () => {
  const multiple = { ...single, maxFiles: 2 };
  assert.equal(fileReferenceUploadIntent(undefined, single), 'CREATE');
  assert.equal(fileReferenceUploadIntent('file-old', single), 'REPLACE');
  assert.equal(fileReferenceUploadIntent(['file-1'], multiple), 'APPEND');
  assert.equal(replacedFileReferenceId('file-old', 'file-new', single), 'file-old');
  assert.equal(replacedFileReferenceId('file-old', 'file-old', single), undefined);
  assert.equal(replacedFileReferenceId(['file-1'], 'file-2', multiple), undefined);
});

test('form-save session only deletes files persisted when the editor opened', () => {
  const session = new FileReferenceFormSaveSession();
  session.begin('file-original');
  // The first replacement removes the original persisted binding exactly once.
  assert.equal(session.remove('file-original'), 'persisted');
  session.registerUploaded('file-upload-1');
  assert.equal(session.remove('file-upload-1'), 'uploaded');
  session.registerUploaded('file-upload-2');
  assert.equal(session.remove('file-upload-2'), 'uploaded');
});

test('form-save session supports multi-file append, removal and re-upload without deleting temporary ids', () => {
  const session = new FileReferenceFormSaveSession();
  session.begin(['file-original-1', 'file-original-2']);
  session.registerUploaded('file-upload-1');
  assert.equal(session.remove('file-upload-1'), 'uploaded');
  session.registerUploaded('file-upload-2');
  assert.equal(session.remove('file-original-2'), 'persisted');
  assert.equal(session.remove('file-upload-2'), 'uploaded');
});

test('file-reference deletion paths preserve child relation identity', () => {
  assert.deepEqual(fileReferenceRecordPath('root-1'), { nodes: [{ recordId: 'root-1' }] });
  assert.deepEqual(fileReferenceRecordPath('root-1', 'items', 'child-1'), {
    nodes: [{ recordId: 'root-1' }, { relationCode: 'items', recordId: 'child-1' }],
  });
  assert.throws(() => fileReferenceRecordPath('root-1', 'items'), /子记录标识/);
});
