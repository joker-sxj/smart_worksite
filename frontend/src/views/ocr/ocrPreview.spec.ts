import { describe, expect, it, vi } from 'vitest';
import { createOcrPreviewController } from './ocrPreview';

describe('createOcrPreviewController', () => {
  it('keeps the current image stable when polling requests the same file repeatedly', async () => {
    const onChange = vi.fn();
    const load = vi.fn().mockResolvedValue({
      blob: new Blob(['image'], { type: 'image/jpeg' }),
      name: 'plate.jpg',
      isImage: true
    });
    const createObjectUrl = vi.fn().mockReturnValue('blob:plate');
    const revokeObjectUrl = vi.fn();
    const controller = createOcrPreviewController({ load, onChange, createObjectUrl, revokeObjectUrl });

    await controller.show(12);
    onChange.mockClear();
    await controller.show(12);

    expect(load).toHaveBeenCalledTimes(1);
    expect(onChange).not.toHaveBeenCalled();
    expect(revokeObjectUrl).not.toHaveBeenCalled();
    expect(controller.current()).toMatchObject({ fileId: 12, url: 'blob:plate', name: 'plate.jpg' });
  });

  it('ignores a stale response after the selected record changes', async () => {
    let resolveFirst: ((value: { blob: Blob; name: string; isImage: boolean }) => void) | undefined;
    const first = new Promise<{ blob: Blob; name: string; isImage: boolean }>((resolve) => { resolveFirst = resolve; });
    const load = vi.fn()
      .mockReturnValueOnce(first)
      .mockResolvedValueOnce({ blob: new Blob(['new']), name: 'new.jpg', isImage: true });
    const revokeObjectUrl = vi.fn();
    const controller = createOcrPreviewController({
      load,
      onChange: vi.fn(),
      createObjectUrl: (blob) => blob.size === 3 ? 'blob:new' : 'blob:old',
      revokeObjectUrl
    });

    const oldRequest = controller.show(1);
    await controller.show(2);
    resolveFirst?.({ blob: new Blob(['older']), name: 'old.jpg', isImage: true });
    await oldRequest;

    expect(controller.current()).toMatchObject({ fileId: 2, url: 'blob:new', name: 'new.jpg' });
    expect(revokeObjectUrl).not.toHaveBeenCalledWith('blob:new');
  });

  it('revokes the previous object URL only when switching files or disposing', async () => {
    const revokeObjectUrl = vi.fn();
    const controller = createOcrPreviewController({
      load: vi.fn()
        .mockResolvedValueOnce({ blob: new Blob(['one']), name: 'one.jpg', isImage: true })
        .mockResolvedValueOnce({ blob: new Blob(['two']), name: 'two.jpg', isImage: true }),
      onChange: vi.fn(),
      createObjectUrl: (blob) => `blob:${blob.size}`,
      revokeObjectUrl
    });

    await controller.show(1);
    await controller.show(2);
    controller.dispose();

    expect(revokeObjectUrl).toHaveBeenNthCalledWith(1, 'blob:3');
    expect(revokeObjectUrl).toHaveBeenNthCalledWith(2, 'blob:3');
  });
});
