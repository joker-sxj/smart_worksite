export function createFileParsePolling(load: () => Promise<boolean>, intervalMs = 2000) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let generation = 0;

  function stop() {
    generation += 1;
    if (timer) clearTimeout(timer);
    timer = undefined;
  }

  async function poll(currentGeneration: number) {
    const active = await load();
    if (!active || currentGeneration !== generation) return;
    timer = setTimeout(() => void poll(currentGeneration), intervalMs);
  }

  async function start() {
    stop();
    const currentGeneration = generation;
    await poll(currentGeneration);
  }

  return { start, stop };
}
