import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { readSchem, snbt } from "./nbt.mjs";

const [snapDir, matchFile, roomsDir, source, ...flags] = process.argv.slice(2);
if (!source) {
  console.error("usage: import-snapshots.mjs <snapshots dir> <match json> <PA rooms dir> <source name> [--dry] [--all]");
  process.exit(1);
}
const dry = flags.includes("--dry");
const all = flags.includes("--all");

const DIRS = ["north", "east", "south", "west"];
const cw = (d, n = 1) => DIRS[(DIRS.indexOf(d) + n) % 4];

function parse(state) {
  const [, name, props = ""] = state.match(/^minecraft:([^[]+)(?:\[(.*)\])?$/);
  const map = {};
  for (const pair of props.split(",").filter(Boolean)) {
    const [k, v] = pair.split("=");
    map[k] = v;
  }
  return { name, props: map };
}

function format({ name, props }) {
  const keys = Object.keys(props).sort();
  return `minecraft:${name}${keys.length ? `[${keys.map((k) => `${k}=${props[k]}`).join(",")}]` : ""}`;
}

function rotate(block, turns) {
  if (turns === 0) return block;
  const p = { ...block.props };
  if (DIRS.includes(p.facing)) p.facing = cw(p.facing, turns);
  if (p.rotation !== undefined) p.rotation = String((Number(p.rotation) + 4 * turns) % 16);
  if (p.axis && turns % 2 === 1) p.axis = p.axis === "x" ? "z" : p.axis === "z" ? "x" : p.axis;
  if (DIRS.some((d) => p[d] !== undefined)) {
    const old = Object.fromEntries(DIRS.map((d) => [d, block.props[d]]));
    for (const d of DIRS) if (old[d] !== undefined) p[cw(d, turns)] = old[d];
  }
  if (p.shape && /rail/.test(block.name)) {
    const parts = p.shape.split("_");
    if (parts[0] === "ascending") p.shape = `ascending_${cw(parts[1], turns)}`;
    else if (p.shape === "north_south" || p.shape === "east_west") p.shape = turns % 2 ? (p.shape === "north_south" ? "east_west" : "north_south") : p.shape;
    else {
      const ds = parts.map((d) => cw(d, turns));
      p.shape = `${ds.find((d) => d === "north" || d === "south")}_${ds.find((d) => d === "east" || d === "west")}`;
    }
  }
  return { name: block.name, props: p };
}

const TO_REL = {
  NW: (x, z, cx, cz) => [x - cx, z - cz],
  NE: (x, z, cx, cz) => [z - cz, -x + cx],
  SW: (x, z, cx, cz) => [-z + cz, x - cx],
  SE: (x, z, cx, cz) => [-x + cx, -z + cz],
};
const TURNS = { NW: 0, NE: 3, SW: 1, SE: 2 };

function cornerFor(facing, cells) {
  const xs = cells.map((c) => c[0]);
  const zs = cells.map((c) => c[1]);
  return {
    NW: [Math.min(...xs), Math.min(...zs)],
    NE: [Math.max(...xs) + 30, Math.min(...zs)],
    SW: [Math.min(...xs), Math.max(...zs) + 30],
    SE: [Math.max(...xs) + 30, Math.max(...zs) + 30],
  }[facing];
}

const indexFile = join(roomsDir, "index.json");
const index = existsSync(indexFile) ? JSON.parse(readFileSync(indexFile, "utf8")) : {};

const snapshots = new Map();
for (const m of JSON.parse(readFileSync(matchFile, "utf8"))) {
  if (m.status !== "matched") continue;
  const [shape, room] = m.pa.split("/");
  const variant = /water-puzzle/.test(room) && typeof m.layout === "number" ? `layout-${m.layout}` : null;
  if (/water-puzzle/.test(room) && !variant) continue;
  const key = variant ? `${shape}/${room}#${variant}` : `${shape}/${room}`;
  if (!snapshots.has(key)) snapshots.set(key, []);
  snapshots.get(key).push({ ...m, shape, room, variant });
}

const AIR = /^minecraft:(air|cave_air|void_air)$/;
const MIN_COVERAGE = 0.99;

const report = { imported: [], kept: [], incomplete: [] };
for (const [key, list] of [...snapshots].sort()) {
  if (!all && index[key]) { report.kept.push(key); continue; }
  list.sort((a, b) => (b.coverage ?? 1) - (a.coverage ?? 1) || a.miss - b.miss);

  let room = null;
  for (const m of list) {
    const meta = JSON.parse(readFileSync(join(snapDir, m.hash, "metadata.json"), "utf8"));
    const s = readSchem(join(snapDir, m.hash, existsSync(join(snapDir, m.hash, "room-v3.schem")) ? "room-v3.schem" : "room.schem"));
    const [ox, oy, oz] = meta.origin;
    const corner = cornerFor(m.facing, m.cells);
    const toRel = (x, z) => TO_REL[m.facing](ox + x, oz + z, corner[0], corner[1]);
    const corners = [[0, 0], [s.w - 1, 0], [0, s.l - 1], [s.w - 1, s.l - 1]].map(([x, z]) => toRel(x, z));
    const minX = Math.min(...corners.map((c) => c[0]));
    const minZ = Math.min(...corners.map((c) => c[1]));
    if (!room) {
      const sizeX = Math.max(...corners.map((c) => c[0])) - minX + 1;
      const sizeZ = Math.max(...corners.map((c) => c[1])) - minZ + 1;
      room = {
        m, meta, oy, h: s.h, minX, minZ, sizeX, sizeZ,
        palette: ["minecraft:air"], paletteIndex: new Map([["minecraft:air", 0]]),
        data: new Array(sizeX * s.h * sizeZ).fill(0), filled: new Set(), wanted: new Set(), blockEntities: [], used: [],
      };
      for (const [x, z] of meta.mask) { const [rx, rz] = toRel(x, z); room.wanted.add((rz - minZ) * sizeX + (rx - minX)); }
    }
    if (oy !== room.oy || s.h !== room.h || minX !== room.minX || minZ !== room.minZ) continue;

    const idOf = (state) => {
      if (!room.paletteIndex.has(state)) { room.paletteIndex.set(state, room.palette.length); room.palette.push(state); }
      return room.paletteIndex.get(state);
    };
    const turns = TURNS[m.facing];
    const rotated = s.palette.map((state) => (AIR.test(state) ? 0 : idOf(format(rotate(parse(state), turns)))));
    const taken = new Set();
    for (let z = 0; z < s.l; z++) for (let x = 0; x < s.w; x++) {
      const [rx, rz] = toRel(x, z);
      const column = (rz - room.minZ) * room.sizeX + (rx - room.minX);
      if (room.filled.has(column)) continue;
      let any = false;
      for (let y = 0; y < s.h && !any; y++) any = rotated[s.data[(y * s.l + z) * s.w + x]] !== 0;
      if (!any) continue;
      for (let y = 0; y < s.h; y++) room.data[y * room.sizeX * room.sizeZ + column] = rotated[s.data[(y * s.l + z) * s.w + x]];
      room.filled.add(column);
      taken.add(column);
    }
    for (const e of s.blockEntities) {
      const [rx, rz] = toRel(e.pos[0], e.pos[2]);
      if (taken.has((rz - room.minZ) * room.sizeX + (rx - room.minX))) room.blockEntities.push({ pos: [rx, oy + e.pos[1], rz], nbt: snbt(e.data) });
    }
    if (taken.size) room.used.push(`${m.hash.slice(0, 8)} ${m.facing}`);
    if ([...room.wanted].every((c) => room.filled.has(c))) break;
  }

  const coverage = [...room.wanted].filter((c) => room.filled.has(c)).length / room.wanted.size;
  const label = `${room.meta.room} -> ${key} (${(100 * coverage).toFixed(1)}% from ${room.used.length} cop${room.used.length === 1 ? "y" : "ies"})`;
  if (coverage < MIN_COVERAGE) { report.incomplete.push(label); continue; }

  const { m } = room;
  const file = m.variant ? join(roomsDir, m.shape, m.room, `${m.variant}.json`) : join(roomsDir, m.shape, `${m.room}.json`);
  const capturedAt = new Date().toISOString();
  if (!dry) {
    mkdirSync(join(file, ".."), { recursive: true });
    writeFileSync(file, JSON.stringify({
      room: m.room, name: room.meta.room, shape: m.shape, variant: m.variant, facing: m.facing, clean: true, floor: null,
      missRate: m.miss / 100, capturedAt, source, cells: m.cells,
      origin: [room.minX, room.oy, room.minZ], size: [room.sizeX, room.h, room.sizeZ], order: "yzx",
      palette: room.palette, data: room.data, blockEntities: room.blockEntities,
    }));
    index[key] = { clean: true, floor: null, capturedAt, missRate: m.miss / 100, source };
  }
  report.imported.push(label);
}

if (!dry) writeFileSync(indexFile, JSON.stringify(Object.fromEntries(Object.entries(index).sort()), null, 2));
console.log(`Imported ${report.imported.length}:\n  ${report.imported.join("\n  ")}`);
console.log(`\nToo incomplete to use ${report.incomplete.length}:\n  ${report.incomplete.join("\n  ")}`);
console.log(`\nAlready in the capture folder ${report.kept.length}`);
