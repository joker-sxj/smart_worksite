export interface OcrPreviewAsset {
  blob: Blob;
  name: string;
  isImage: boolean;
}

export interface OcrPreviewSnapshot {
  fileId?: string | number;
  url: string;
  name: string;
  isImage: boolean;
  error: string;
}

interface OcrPreviewControllerOptions {
  load: (fileId: string | number) => Promise<OcrPreviewAsset>;
  onChange: (snapshot: OcrPreviewSnapshot) => void;
  createObjectUrl?: (blob: Blob) => string;
  revokeObjectUrl?: (url: string) => void;
}

const EMPTY_PREVIEW: OcrPreviewSnapshot = {
  url: '',
  name: '',
  isImage: false,
  error: ''
};

export function createOcrPreviewController(options: OcrPreviewControllerOptions) {
  const createObjectUrl = options.createObjectUrl || ((blob: Blob) => URL.createObjectURL(blob));
  const revokeObjectUrl = options.revokeObjectUrl || ((url: string) => URL.revokeObjectURL(url));
  let snapshot: OcrPreviewSnapshot = { ...EMPTY_PREVIEW };
  let requestVersion = 0;
  let loadingFileId = '';
  let pending: Promise<OcrPreviewSnapshot> | undefined;

  function publish(next: OcrPreviewSnapshot) {
    snapshot = next;
    options.onChange({ ...snapshot });
    return snapshot;
  }

  function releaseCurrent() {
    if (snapshot.url) revokeObjectUrl(snapshot.url);
  }

  function clear() {
    requestVersion += 1;
    loadingFileId = '';
    pending = undefined;
    releaseCurrent();
    publish({ ...EMPTY_PREVIEW });
  }

  function show(fileId: string | number): Promise<OcrPreviewSnapshot> {
    const normalizedFileId = String(fileId);
    if (String(snapshot.fileId || '') === normalizedFileId && snapshot.url) {
      return Promise.resolve(snapshot);
    }
    if (loadingFileId === normalizedFileId && pending) return pending;

    const previousFileId = snapshot.fileId;
    const version = ++requestVersion;
    loadingFileId = normalizedFileId;
    if (previousFileId && String(previousFileId) !== normalizedFileId) {
      releaseCurrent();
      publish({ ...EMPTY_PREVIEW, fileId });
    }

    pending = options.load(fileId)
      .then((asset) => {
        if (version !== requestVersion) return snapshot;
        const objectUrl = createObjectUrl(asset.blob);
        if (version !== requestVersion) {
          revokeObjectUrl(objectUrl);
          return snapshot;
        }
        if (snapshot.url && snapshot.url !== objectUrl) revokeObjectUrl(snapshot.url);
        return publish({
          fileId,
          url: objectUrl,
          name: asset.name,
          isImage: asset.isImage,
          error: ''
        });
      })
      .catch((error: unknown) => {
        if (version !== requestVersion) return snapshot;
        const message = error instanceof Error ? error.message : '原图预览加载失败';
        return publish({
          ...snapshot,
          fileId,
          error: message
        });
      })
      .finally(() => {
        if (version === requestVersion) {
          loadingFileId = '';
          pending = undefined;
        }
      });
    return pending;
  }

  return {
    show,
    clear,
    dispose: clear,
    current: () => ({ ...snapshot })
  };
}
