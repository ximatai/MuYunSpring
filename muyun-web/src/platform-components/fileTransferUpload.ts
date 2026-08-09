/**
 * The platform owns the browser-to-storage transfer mechanics, while an
 * application owns the authorization request. A caller may additionally run a
 * business confirmation step, while standard file-reference fields only bind
 * the returned fileId during their later record save.
 * MuYunFileServer owns the multipart transport; this module deliberately has
 * no knowledge of a particular business record type.
 */
export interface FileTransferUploadAccess {
  /** A short-lived MuYunFileServer multipart upload URL supplied by the business API. */
  uploadUrl: string;
  formFieldName?: string;
}

export interface FileTransferUploadReceipt {
  file: File;
  /** Storage service response after unwrapping the conventional { data } envelope. */
  payload: unknown;
  response: unknown;
}

export interface FileTransferUploadTask {
  promise: Promise<FileTransferUploadReceipt>;
  cancel: () => void;
}

/** Browser-visible phases shared by upload controls. */
export type FileTransferUploadState = 'requesting' | 'uploading' | 'confirming' | 'completed';

export interface FileTransferUploadLifecycleCallbacks {
  stateChanged: (state: FileTransferUploadState) => void;
  progressChanged?: (percent: number) => void;
  taskCreated?: (task: FileTransferUploadTask) => void;
  taskFinished?: () => void;
}

/**
 * Runs the common browser upload lifecycle without knowing the business record being edited.
 *
 * State is reported through callbacks so the consuming control can update its own reactive item. When supplied,
 * business confirmation completes before the completed transition; otherwise the raw storage payload is returned.
 */
export async function performBrowserFileTransferUpload(
  file: File,
  requestUploadAccess: (file: File) => Promise<FileTransferUploadAccess>,
  confirmUpload: ((receipt: FileTransferUploadReceipt) => Promise<unknown>) | undefined,
  callbacks: FileTransferUploadLifecycleCallbacks,
): Promise<{ receipt: FileTransferUploadReceipt; result: unknown }> {
  callbacks.stateChanged('requesting');
  const access = await requestUploadAccess(file);
  callbacks.stateChanged('uploading');
  const task = createBrowserFileTransferUpload(file, access, callbacks.progressChanged);
  callbacks.taskCreated?.(task);
  try {
    const receipt = await task.promise;
    callbacks.taskFinished?.();
    const result = confirmUpload
      ? await confirmUploadedFile(receipt, confirmUpload, callbacks)
      : receipt.payload;
    callbacks.stateChanged('completed');
    return { receipt, result };
  } catch (error) {
    callbacks.taskFinished?.();
    throw error;
  }
}

async function confirmUploadedFile(
  receipt: FileTransferUploadReceipt,
  confirmUpload: (receipt: FileTransferUploadReceipt) => Promise<unknown>,
  callbacks: FileTransferUploadLifecycleCallbacks,
) {
  callbacks.stateChanged('confirming');
  return confirmUpload(receipt);
}

export function createBrowserFileTransferUpload(
  file: File,
  access: FileTransferUploadAccess,
  onProgress?: (percent: number) => void,
): FileTransferUploadTask {
  let request: XMLHttpRequest | undefined;
  const promise = new Promise<FileTransferUploadReceipt>((resolve, reject) => {
    if (!access.uploadUrl.trim()) {
      reject(new Error('上传凭证未提供上传地址。'));
      return;
    }
    request = new XMLHttpRequest();
    request.open('POST', access.uploadUrl, true);
    request.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        onProgress?.(Math.round((event.loaded / event.total) * 100));
      }
    };
    request.onerror = () => reject(new Error('文件服务网络连接失败。'));
    request.onabort = () => reject(new Error('上传已取消。'));
    request.onload = () => {
      const response = parseResponse(request?.responseText);
      if (!request || request.status < 200 || request.status >= 300) {
        reject(new Error(responseMessage(response) ?? `文件服务上传失败（HTTP ${request?.status ?? 0}）。`));
        return;
      }
      onProgress?.(100);
      resolve({ file, response, payload: unwrapResponsePayload(response) });
    };
    const formData = new FormData();
    formData.append(access.formFieldName ?? 'files', file, file.name);
    request.send(formData);
  });
  return { promise, cancel: () => request?.abort() };
}

export function unwrapResponsePayload(response: unknown): unknown {
  if (isRecord(response) && 'data' in response) {
    return response.data;
  }
  return response;
}

function parseResponse(value: string | undefined): unknown {
  if (!value) {
    return undefined;
  }
  try {
    return JSON.parse(value) as unknown;
  } catch {
    return value;
  }
}

function responseMessage(response: unknown): string | undefined {
  if (!isRecord(response)) {
    return typeof response === 'string' && response.trim() ? response : undefined;
  }
  for (const key of ['message', 'error', 'detail']) {
    if (typeof response[key] === 'string' && response[key].trim()) {
      return response[key];
    }
  }
  return undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
