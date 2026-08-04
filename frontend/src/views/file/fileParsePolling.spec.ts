import { afterEach, describe, expect, it, vi } from 'vitest';
import { createFileParsePolling } from './fileParsePolling';

afterEach(() => vi.useRealTimers());

describe('createFileParsePolling', () => {
  it('refreshes repeatedly while load reports an active parse', async () => {
    vi.useFakeTimers();
    const load = vi.fn().mockResolvedValue(true);
    const polling = createFileParsePolling(load, 2000);

    await polling.start();
    expect(load).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(2000);
    expect(load).toHaveBeenCalledTimes(2);
  });

  it('stops scheduling after load reports terminal records', async () => {
    vi.useFakeTimers();
    const load = vi.fn().mockResolvedValueOnce(true).mockResolvedValueOnce(false);
    const polling = createFileParsePolling(load, 2000);

    await polling.start();
    await vi.advanceTimersByTimeAsync(2000);
    await vi.advanceTimersByTimeAsync(4000);

    expect(load).toHaveBeenCalledTimes(2);
  });

  it('cancels the old timer when restarted or stopped', async () => {
    vi.useFakeTimers();
    const load = vi.fn().mockResolvedValue(true);
    const polling = createFileParsePolling(load, 2000);

    await polling.start();
    await polling.start();
    polling.stop();
    await vi.advanceTimersByTimeAsync(4000);

    expect(load).toHaveBeenCalledTimes(2);
  });
});
