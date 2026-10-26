// Worker: builds chunk geometry off the main thread so panning never stalls
// while a scene is processed. Buffers are transferred back, not copied.
import { buildChunk, type BuildContext, type BuildOptions, type BuiltChunk } from "./chunkBuild";
import type { SceneData } from "./sceneData";

interface BuildRequest {
  id: number;
  data: SceneData;
  ctx: BuildContext;
  opts: BuildOptions;
}

const worker = self as unknown as {
  onmessage: ((e: MessageEvent<BuildRequest>) => void) | null;
  postMessage(message: unknown, transfer: Transferable[]): void;
};

worker.onmessage = (e) => {
  const { id, data, ctx, opts } = e.data;
  try {
    const chunk = buildChunk(data, ctx, opts);
    const transfer: Transferable[] = [];
    for (const a of chunk.areas) transfer.push(a.pos.buffer);
    for (const r of chunk.roads) transfer.push(r.pos.buffer);
    if (chunk.buildings) {
      transfer.push(chunk.buildings.pos.buffer, chunk.buildings.normals.buffer, chunk.buildings.colors.buffer);
    }
    worker.postMessage({ id, chunk }, transfer);
  } catch (err) {
    worker.postMessage({ id, error: err instanceof Error ? err.message : String(err) }, []);
  }
};
