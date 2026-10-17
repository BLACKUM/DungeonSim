import { readFileSync, readdirSync, statSync, existsSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { inflateSync } from "node:zlib";
import { readSchem } from "./nbt.mjs";
const [snapDir, skDir, outFile] = process.argv.slice(2);
const GRID = -200, CELL = 32, MAX_MISS = 0.05, MIN_LEAD = 0.02, MIN_HITS = 200;
const POOLS1 = ["1x1", "puzzle", "trap", "miniboss"];
const NUMERIC_ID = { stone: 1, diorite: 2, polished_diorite: 3, andesite: 4, polished_andesite: 5, grass_block: 6, dirt: 7, coarse_dirt: 8, cobblestone: 9, bedrock: 10, oak_leaves: 11, gray_wool: 12, double_stone_slab: 13, mossy_cobblestone: 14, clay: 15, stone_bricks: 16, mossy_stone_bricks: 17, chiseled_stone_bricks: 18, gray_terracotta: 19, cyan_terracotta: 20, black_terracotta: 21 };
function skeleton(f) { const b = inflateSync(readFileSync(f)); for (let n = Math.floor((b.length - 4) / 4); n > 0; n--) { const at = b.length - 4 * n - 4; if (b.readInt32BE(at) === n) { const v = new Set(); for (let i = 0; i < n; i++) v.add(b.readInt32BE(at + 4 + 4 * i)); return v; } } }
const sks = {}; for (const s of readdirSync(skDir)) { if (!statSync(join(skDir, s)).isDirectory()) continue; sks[s] = {}; for (const f of readdirSync(join(skDir, s))) if (f.endsWith(".skeleton")) sks[s][f.replace(".skeleton", "")] = skeleton(join(skDir, s, f)); }
const TO_REL = { NW: (x, z, cx, cz) => [x - cx, z - cz], NE: (x, z, cx, cz) => [z - cz, -x + cx], SW: (x, z, cx, cz) => [-z + cz, x - cx], SE: (x, z, cx, cz) => [-x + cx, -z + cz] };
const cornerFor = (f, cells) => { const xs = cells.map((c) => c[0]), zs = cells.map((c) => c[1]); return { NW: [Math.min(...xs), Math.min(...zs)], NE: [Math.max(...xs) + 30, Math.min(...zs)], SW: [Math.min(...xs), Math.max(...zs) + 30], SE: [Math.max(...xs) + 30, Math.max(...zs) + 30] }[f]; };
function facingsFor(shape, cells) { const xs = [...new Set(cells.map((c) => c[0]))].sort((a, b) => a - b), zs = [...new Set(cells.map((c) => c[1]))].sort((a, b) => a - b); const has = (x, z) => cells.some((c) => c[0] === x && c[1] === z);
  if (["1x2", "1x3", "1x4"].includes(shape)) { if (xs.length > 1 && zs.length === 1) return ["NW", "SE"]; if (xs.length === 1 && zs.length > 1) return ["NE", "SW"]; }
  if (shape === "l-shape") { if (!has(xs[0], zs[0])) return ["SW"]; if (!has(xs[0], zs.at(-1))) return ["SE"]; if (!has(xs.at(-1), zs[0])) return ["NW"]; if (!has(xs.at(-1), zs.at(-1))) return ["NE"]; }
  return ["NW", "NE", "SW", "SE"]; }
const shapeOf = (cells) => { const xs = new Set(cells.map((c) => c[0])).size, zs = new Set(cells.map((c) => c[1])).size; if (cells.length === 1) return "1x1"; if (cells.length === 2) return "1x2"; if (cells.length === 3) return xs === 2 && zs === 2 ? "l-shape" : "1x3"; return xs === 2 && zs === 2 ? "2x2" : "1x4"; };
const notInDoorway = (x, y, z) => { if (y < 66 || y > 73) return true; const mx = (((x - 8) % 32) + 32) % 32, mz = (((z - 8) % 32) + 32) % 32; return (mx < 13 || mx > 17 || (mz > 2 && mz < 28)) && (mz < 13 || mz > 17 || (mx > 2 && mx < 28)); };
const out = [];
for (const h of readdirSync(snapDir).sort()) {
  const m = join(snapDir, h, "metadata.json"); if (!existsSync(m)) continue;
  const meta = JSON.parse(readFileSync(m)); const sf = existsSync(join(snapDir, h, "room-v3.schem")) ? "room-v3.schem" : "room.schem";
  const s = readSchem(join(snapDir, h, sf)); const [ox, oy, oz] = meta.origin;
  const cells = [...new Set(meta.mask.map(([mx, mz]) => `${Math.floor((ox + mx - GRID) / CELL) * CELL + GRID},${Math.floor((oz + mz - GRID) / CELL) * CELL + GRID}`))].map((k) => k.split(",").map(Number));
  const shape = shapeOf(cells);
  const blocks = [];
  for (let y = 0; y < s.h; y++) for (let z = 0; z < s.l; z++) for (let x = 0; x < s.w; x++) { const n = s.palette[s.data[(y * s.l + z) * s.w + x]].replace("minecraft:", "").split("[")[0]; blocks.push([ox + x, oy + y, oz + z, n]); }
  const cands = []; for (const f of facingsFor(shape, cells)) { const c = cornerFor(f, cells); for (const pool of shape === "1x1" ? POOLS1 : [shape]) for (const [room, sk] of Object.entries(sks[pool] ?? {})) cands.push({ room: pool + "/" + room, f, c, sk, hits: 0, misses: 0 }); }
  for (const [x, y, z, n] of blocks) { const id = NUMERIC_ID[n]; if (!id || !notInDoorway(x, y, z)) continue; for (const c of cands) { const [rx, rz] = TO_REL[c.f](x, z, c.c[0], c.c[1]); if (c.sk.has((rx << 24) | (y << 16) | (rz << 8) | id)) c.hits++; else c.misses++; } }
  const rate = (c) => c.misses / Math.max(1, c.hits + c.misses);
  const ranked = cands.filter((c) => c.hits >= MIN_HITS).sort((a, b) => rate(a) - rate(b)); const best = ranked[0];
  let status = "matched"; if (!best || rate(best) > MAX_MISS) status = "nomatch"; else { const ru = ranked.find((c) => c.room !== best.room); if (ru && rate(ru) - rate(best) < Math.max(MIN_LEAD, rate(best))) status = "ambiguous:" + ru.room; }
  let layout = null;
  if (best && /water-puzzle/.test(best.room)) { const found = new Set(); for (const [x, y, z, n] of blocks) { const [rx, rz] = TO_REL[best.f](x, z, best.c[0], best.c[1]); if (rx >= 14 && rx <= 16 && y >= 77 && y <= 78 && rz >= 26 && rz <= 27) found.add(n); }
    layout = found.has("gold_block") && found.has("terracotta") ? 1 : found.has("emerald_block") && found.has("quartz_block") ? 2 : found.has("quartz_block") && found.has("diamond_block") ? 3 : found.has("gold_block") && found.has("quartz_block") ? 4 : "?" + [...found].join("-"); }
  out.push({ hash: h, name: meta.room, shape, cells, pa: best?.room, facing: best?.f, miss: best ? +(100 * rate(best)).toFixed(2) : null, status, layout, size: [s.w, s.h, s.l], mc: meta.minecraftVersion });
}
writeFileSync(outFile, JSON.stringify(out, null, 1));
console.log("done", out.length);
