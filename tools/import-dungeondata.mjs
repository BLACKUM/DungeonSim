import { readFileSync, writeFileSync, readdirSync, existsSync, mkdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { inflateSync } from "node:zlib";

const [ddDir, legacyFile, skeletonDir, roomsDir, ...flags] = process.argv.slice(2);
const dry = flags.includes("--dry");
if (!roomsDir) {
  console.error("usage: import-dungeondata.mjs <DungeonData dir> <legacy.json> <PA skeleton dir> <PA rooms dir> [--dry]");
  process.exit(1);
}

const GRID = -200;
const CELL = 32;
const MAX_MISS_RATE = 0.05;
const MIN_LEAD = 0.02;
const MIN_HITS = 50;
const ONE_BY_ONE_POOLS = ["1x1", "puzzle", "trap", "miniboss"];
const NUMERIC_ID = {
  stone: 1, diorite: 2, polished_diorite: 3, andesite: 4, polished_andesite: 5, grass_block: 6, dirt: 7,
  coarse_dirt: 8, cobblestone: 9, bedrock: 10, oak_leaves: 11, gray_wool: 12, double_stone_slab: 13,
  mossy_cobblestone: 14, clay: 15, stone_bricks: 16, mossy_stone_bricks: 17, chiseled_stone_bricks: 18,
  gray_terracotta: 19, cyan_terracotta: 20, black_terracotta: 21,
};

const legacy = JSON.parse(readFileSync(legacyFile, "utf8")).blocks;
const RENAMES = { grass: "short_grass", sign: "oak_sign", wall_sign: "oak_wall_sign", grass_path: "dirt_path", stone_slab: "smooth_stone_slab" };
const SKULLS = ["skeleton", "wither_skeleton", "zombie", "player", "creeper", "dragon"];

function parse(state) {
  const [, name, props = ""] = state.match(/^minecraft:([^[]+)(?:\[(.*)\])?$/);
  const map = {};
  for (const pair of props.split(",").filter(Boolean)) {
    const [k, v] = pair.split("=");
    map[k] = v;
  }
  return { name: RENAMES[name] ?? name, props: map };
}

function format({ name, props }) {
  const keys = Object.keys(props).sort();
  return `minecraft:${name}${keys.length ? `[${keys.map((k) => `${k}=${props[k]}`).join(",")}]` : ""}`;
}

const DIRS = ["north", "east", "south", "west"];
const OFFSET = { north: [0, -1], east: [1, 0], south: [0, 1], west: [-1, 0] };
const cw = (d, n = 1) => DIRS[(DIRS.indexOf(d) + n) % 4];
const opposite = (d) => cw(d, 2);
const axisOf = (d) => (d === "north" || d === "south" ? "z" : "x");

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
      const ns = ds.find((d) => d === "north" || d === "south");
      const ew = ds.find((d) => d === "east" || d === "west");
      p.shape = `${ns}_${ew}`;
    }
  }
  return { name: block.name, props: p };
}

const NOT_FULL = /^(air|cave_air|water|lava|cobweb|ladder|vine|snow|cactus|cake|anvil|hopper|brewing_stand|cauldron|enchanting_table|tripwire|tripwire_hook|redstone_wire|repeater|comparator|lily_pad|sugar_cane|wheat|carrots|potatoes|beetroots|nether_wart|cocoa|end_rod|piston_head|daylight_detector|dead_bush|short_grass|tall_grass|fern|large_fern|dandelion|poppy|blue_orchid|allium|azure_bluet|oxeye_daisy|sunflower|lilac|rose_bush|peony|brown_mushroom|red_mushroom|flower_pot|lever|torch|wall_torch|redstone_torch|redstone_wall_torch|chest|trapped_chest|ender_chest|end_portal_frame|nether_portal|iron_bars)$|_(slab|stairs|fence|fence_gate|pane|wall|door|trapdoor|sign|wall_sign|banner|wall_banner|skull|wall_skull|head|wall_head|carpet|button|pressure_plate|rail|sapling|bed|tulip|potted_.*)$|^rail$|^potted_/;
const isFull = (b) => b && !NOT_FULL.test(b.name);
const isFence = (b) => b && /_fence$/.test(b.name);
const isGate = (b) => b && /_fence_gate$/.test(b.name);
const isPane = (b) => b && (/glass_pane$/.test(b.name) || b.name === "iron_bars");
const isWall = (b) => b && /_wall$/.test(b.name) && !/sign|banner|torch|head|skull/.test(b.name);
const isStairs = (b) => b && /_stairs$/.test(b.name);
const POST_OVERRIDE = /^(torch|soul_torch|redstone_torch|tripwire)$|_(sign|banner|pressure_plate)$/;

function fixShapes(grid, sx, sy, sz) {
  const at = (x, y, z) => (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz ? null : grid[(y * sz + z) * sx + x]);
  const out = grid.slice();
  const near = (x, y, z, d) => at(x + OFFSET[d][0], y, z + OFFSET[d][1]);
  const paneConn = (x, y, z) => DIRS.filter((d) => { const n = near(x, y, z, d); return isPane(n) || isWall(n) || isFull(n); });
  const wallConn = (x, y, z) => DIRS.filter((d) => { const n = near(x, y, z, d); return isWall(n) || isPane(n) || isGate(n) || isFull(n); });
  const wallState = (x, y, z) => {
    const conn = wallConn(x, y, z);
    const above = at(x, y + 1, z);
    const aboveWall = isWall(above) ? wallState(x, y + 1, z) : null;
    const coversAll = isFull(above) || /_slab$/.test(above?.name ?? "") && above.props.type !== "top" || isStairs(above) && above.props.half === "bottom";
    const coversSide = (d) => coversAll || (aboveWall && aboveWall[d] !== "none") || (isPane(above) && (paneConn(x, y + 1, z).length === 0 || paneConn(x, y + 1, z).includes(d)));
    const s = {};
    for (const d of DIRS) s[d] = !conn.includes(d) ? "none" : coversSide(d) ? "tall" : "low";
    const none = (d) => s[d] === "none";
    let up;
    if (aboveWall?.up === "true") up = true;
    else if (DIRS.every(none) || none("north") !== none("south") || none("east") !== none("west")) up = true;
    else if ((s.north === "tall" && s.south === "tall") || (s.east === "tall" && s.west === "tall")) up = false;
    else up = POST_OVERRIDE.test(above?.name ?? "") || coversAll || /_(skull|head)$/.test(above?.name ?? "") || (aboveWall?.up === "true");
    s.up = String(up);
    return s;
  };
  for (let y = 0; y < sy; y++) for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
    const b = at(x, y, z);
    if (!b || b.name === "air") continue;
    const side = (d) => at(x + OFFSET[d][0], y, z + OFFSET[d][1]);
    const p = { ...b.props };

    if (isFence(b)) {
      for (const d of DIRS) {
        const n = side(d);
        const sameKind = isFence(n) && (n.name === "nether_brick_fence") === (b.name === "nether_brick_fence");
        p[d] = String(sameKind || isGate(n) || isFull(n));
      }
    } else if (isPane(b)) {
      const conn = paneConn(x, y, z);
      for (const d of DIRS) p[d] = String(conn.length === 0 || conn.includes(d));
    } else if (isWall(b)) {
      Object.assign(p, wallState(x, y, z));
    } else if (b.name === "hopper") {
      p.enabled = "true";
    } else if (b.name === "cauldron") {
      const level = Number(p.level ?? 0);
      out[(y * sz + z) * sx + x] = level > 0 ? { name: "water_cauldron", props: { level: String(level) } } : { name: "cauldron", props: {} };
      continue;
    } else if (isStairs(b)) {
      const f = b.props.facing;
      const canTake = (face) => { const n = side(face); return !isStairs(n) || n.props.facing !== f || n.props.half !== b.props.half; };
      let shape = "straight";
      const front = side(f);
      const back = side(opposite(f));
      if (isStairs(front) && front.props.half === b.props.half && axisOf(front.props.facing) !== axisOf(f) && canTake(opposite(front.props.facing))) {
        shape = front.props.facing === cw(f, 3) ? "outer_left" : "outer_right";
      } else if (isStairs(back) && back.props.half === b.props.half && axisOf(back.props.facing) !== axisOf(f) && canTake(back.props.facing)) {
        shape = back.props.facing === cw(f, 3) ? "inner_left" : "inner_right";
      }
      p.shape = shape;
    } else if (/_door$/.test(b.name)) {
      const other = at(x, y + (p.half === "lower" ? 1 : -1), z);
      if (other && other.name === b.name) {
        if (p.half === "lower") { p.hinge = other.props.hinge ?? p.hinge; p.powered = other.props.powered ?? p.powered; }
        else { p.facing = other.props.facing ?? p.facing; p.open = other.props.open ?? p.open; }
      }
    } else if (p.half === "upper" && /sunflower|lilac|tall_grass|large_fern|rose_bush|peony/.test(b.name)) {
      const below = at(x, y - 1, z);
      if (below && below.props.half === "lower") { out[(y * sz + z) * sx + x] = { name: below.name, props: { half: "upper" } }; continue; }
    } else if (b.name === "chest" || b.name === "trapped_chest") {
      const f = p.facing;
      const partner = (d) => { const n = side(d); return n && n.name === b.name && n.props.facing === f; };
      p.type = partner(cw(f)) ? "left" : partner(cw(f, 3)) ? "right" : "single";
    }
    out[(y * sz + z) * sx + x] = { name: b.name, props: p };
  }
  return out;
}

function skeleton(file) {
  const bytes = inflateSync(readFileSync(file));
  for (let n = Math.floor((bytes.length - 4) / 4); n > 0; n--) {
    const at = bytes.length - 4 * n - 4;
    if (bytes.readInt32BE(at) === n) {
      const values = new Int32Array(n);
      for (let i = 0; i < n; i++) values[i] = bytes.readInt32BE(at + 4 + 4 * i);
      return new Set(values);
    }
  }
  throw new Error(`can't read ${file}`);
}

const skeletons = {};
for (const shape of readdirSync(skeletonDir)) {
  if (!statSync(join(skeletonDir, shape)).isDirectory()) continue;
  skeletons[shape] = {};
  for (const f of readdirSync(join(skeletonDir, shape))) {
    if (f.endsWith(".skeleton")) skeletons[shape][f.replace(".skeleton", "")] = skeleton(join(skeletonDir, shape, f));
  }
}

const TO_REL = {
  NW: (x, z, cx, cz) => [x - cx, z - cz],
  NE: (x, z, cx, cz) => [z - cz, -x + cx],
  SW: (x, z, cx, cz) => [-z + cz, x - cx],
  SE: (x, z, cx, cz) => [-x + cx, -z + cz],
};
const TO_REL_POINT = {
  NW: (x, z, cx, cz) => [x - cx, z - cz],
  NE: (x, z, cx, cz) => [z - cz, cx - x + 1],
  SW: (x, z, cx, cz) => [cz - z + 1, x - cx],
  SE: (x, z, cx, cz) => [cx - x + 1, cz - z + 1],
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

function facingsFor(shape, cells) {
  const xs = [...new Set(cells.map((c) => c[0]))].sort((a, b) => a - b);
  const zs = [...new Set(cells.map((c) => c[1]))].sort((a, b) => a - b);
  const has = (x, z) => cells.some((c) => c[0] === x && c[1] === z);
  if (["1x2", "1x3", "1x4"].includes(shape)) {
    if (xs.length > 1 && zs.length === 1) return ["NW", "SE"];
    if (xs.length === 1 && zs.length > 1) return ["NE", "SW"];
  }
  if (shape === "l-shape") {
    if (!has(xs[0], zs[0])) return ["SW"];
    if (!has(xs[0], zs.at(-1))) return ["SE"];
    if (!has(xs.at(-1), zs[0])) return ["NW"];
    if (!has(xs.at(-1), zs.at(-1))) return ["NE"];
  }
  return ["NW", "NE", "SW", "SE"];
}

function shapeOf(cells) {
  const xs = new Set(cells.map((c) => c[0])).size;
  const zs = new Set(cells.map((c) => c[1])).size;
  if (cells.length === 1) return "1x1";
  if (cells.length === 2) return "1x2";
  if (cells.length === 3) return xs === 2 && zs === 2 ? "l-shape" : "1x3";
  return xs === 2 && zs === 2 ? "2x2" : "1x4";
}

function notInDoorway(x, y, z) {
  if (y < 66 || y > 73) return true;
  const mx = (((x - 8) % 32) + 32) % 32;
  const mz = (((z - 8) % 32) + 32) % 32;
  return (mx < 13 || mx > 17 || (mz > 2 && mz < 28)) && (mz < 13 || mz > 17 || (mx > 2 && mx < 28));
}

function match(blocks, cells, shape) {
  const pools = shape === "1x1" ? ONE_BY_ONE_POOLS : [shape];
  const candidates = [];
  for (const facing of facingsFor(shape, cells)) {
    const corner = cornerFor(facing, cells);
    for (const pool of pools) for (const [room, sk] of Object.entries(skeletons[pool] ?? {})) {
      candidates.push({ room, pool, facing, corner, sk, hits: 0, misses: 0 });
    }
  }
  for (const [x, y, z, id] of blocks) {
    if (!notInDoorway(x, y, z)) continue;
    for (const c of candidates) {
      const [rx, rz] = TO_REL[c.facing](x, z, c.corner[0], c.corner[1]);
      if (c.sk.has((rx << 24) | (y << 16) | (rz << 8) | id)) c.hits++;
      else c.misses++;
    }
  }
  const rate = (c) => c.misses / Math.max(1, c.hits + c.misses);
  const ranked = candidates.filter((c) => c.hits >= MIN_HITS).sort((a, b) => rate(a) - rate(b));
  const best = ranked[0];
  if (!best || rate(best) > MAX_MISS_RATE) return { best, rate: best ? rate(best) : 1 };
  const runnerUp = ranked.find((c) => c.room !== best.room);
  if (runnerUp && rate(runnerUp) - rate(best) < Math.max(MIN_LEAD, rate(best))) return { best, rate: rate(best), ambiguous: runnerUp };
  return { matched: best, rate: rate(best) };
}

const indexFile = join(roomsDir, "index.json");
const index = existsSync(indexFile) ? JSON.parse(readFileSync(indexFile, "utf8")) : {};

const report = { imported: [], decorated: [], skipped: [], unmatched: [] };

for (const file of readdirSync(join(ddDir, "room_data")).sort()) {
  const room = JSON.parse(readFileSync(join(ddDir, "room_data", file), "utf8"));
  const { width: sx, length: sz, height: sy, bottom } = room;

  const blockEntities = new Map((room.block_entities ?? []).map((e) => [`${e.position[0]},${e.position[1]},${e.position[2]}`, e.block_entity]));

  let grid = new Array(sx * sy * sz);
  for (let i = 0; i < grid.length; i++) {
    const raw = parseInt(room.block_data.substr(i * 4, 4), 16);
    const block = parse(legacy[`${raw >> 4}:${raw & 15}`] ?? "minecraft:air");
    if (/skull$/.test(block.name)) {
      const x = i % sx, z = Math.floor(i / sx) % sz, y = Math.floor(i / (sx * sz));
      const type = SKULLS[blockEntities.get(`${x},${y},${z}`)?.skull_type ?? 0] ?? "skeleton";
      const head = type === "skeleton" || type === "wither_skeleton" ? `${type}_skull` : `${type}_head`;
      const wall = block.name.includes("wall");
      block.name = wall ? head.replace(/_(skull|head)$/, "_wall_$1") : head;
      if (!wall) block.props = { rotation: String(blockEntities.get(`${x},${y},${z}`)?.rotation ?? block.props.rotation ?? 0) };
    }
    grid[i] = block;
  }
  grid = fixShapes(grid, sx, sy, sz);

  const cellsX = Math.round((sx + 1) / CELL);
  const cellsZ = Math.round((sz + 1) / CELL);
  const cells = [];
  for (let cx = 0; cx < cellsX; cx++) for (let cz = 0; cz < cellsZ; cz++) {
    let any = false;
    for (let y = 0; y < sy && !any; y++) for (let z = cz * CELL; z < cz * CELL + 31 && !any; z++) for (let x = cx * CELL; x < cx * CELL + 31 && !any; x++) {
      if (grid[(y * sz + z) * sx + x].name !== "air") any = true;
    }
    if (any) cells.push([GRID + cx * CELL, GRID + cz * CELL]);
  }
  const shape = shapeOf(cells);

  const matchable = [];
  for (let y = 0; y < sy; y++) for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
    const id = NUMERIC_ID[grid[(y * sz + z) * sx + x].name];
    if (id) matchable.push([GRID + x, bottom + y, GRID + z, id]);
  }
  const result = match(matchable, cells, shape);
  if (!result.matched) {
    report.unmatched.push(`${room.name} [${room.type} ${shape}] best ${result.best ? `${result.best.room} ${(100 * result.rate).toFixed(1)}%` : "none"}${result.ambiguous ? ` too close to ${result.ambiguous.room}` : ""}`);
    continue;
  }
  const { room: paRoom, pool, facing, corner } = result.matched;
  const key = `${pool}/${paRoom}`;
  const turns = TURNS[facing];

  const corners = [[0, 0], [sx - 1, 0], [0, sz - 1], [sx - 1, sz - 1]].map(([x, z]) => TO_REL[facing](GRID + x, GRID + z, corner[0], corner[1]));
  const relMinX = Math.min(...corners.map((c) => c[0]));
  const relMinZ = Math.min(...corners.map((c) => c[1]));
  const relSizeX = Math.max(...corners.map((c) => c[0])) - relMinX + 1;
  const relSizeZ = Math.max(...corners.map((c) => c[1])) - relMinZ + 1;
  const outSizeY = sy + 1;
  const palette = ["minecraft:air"];
  const paletteIndex = new Map([["minecraft:air", 0]]);
  const data = new Array(relSizeX * outSizeY * relSizeZ).fill(0);
  const idOf = (state) => {
    if (!paletteIndex.has(state)) { paletteIndex.set(state, palette.length); palette.push(state); }
    return paletteIndex.get(state);
  };
  const hasCell = (ix, iz) => cells.some(([cx, cz]) => cx === GRID + ix * CELL && cz === GRID + iz * CELL);
  const touching = (v) => (v % CELL === 31 ? [Math.floor(v / CELL), Math.floor(v / CELL) + 1] : [Math.floor(v / CELL)]);
  const covered = (x, z) => touching(x).every((ix) => touching(z).every((iz) => hasCell(ix, iz)));
  const stone = idOf("minecraft:stone");
  for (let z = 0; z < sz; z++) for (let x = 0; x < sx; x++) {
    const [rx, rz] = TO_REL[facing](GRID + x, GRID + z, corner[0], corner[1]);
    const column = (rz - relMinZ) * relSizeX + (rx - relMinX);
    for (let y = 0; y < sy; y++) {
      const block = grid[(y * sz + z) * sx + x];
      if (block.name === "air") continue;
      data[y * relSizeX * relSizeZ + column] = idOf(format(rotate(block, turns)));
    }
    if (covered(x, z)) data[sy * relSizeX * relSizeZ + column] = stone;
  }

  const legacyBlockEntities = (room.block_entities ?? []).map((e) => {
    const [x, y, z] = e.position;
    const [rx, rz] = TO_REL[facing](GRID + x, GRID + z, corner[0], corner[1]);
    return { pos: [rx, bottom + y, rz], ...e.block_entity, ...(e.block_entity.rotation !== undefined ? { rotation: (e.block_entity.rotation + 4 * turns) % 16 } : {}) };
  });

  const entities = (room.prop_entities ?? []).map((e) => {
    const [x, y, z] = e.position;
    const [rx, rz] = TO_REL_POINT[facing](GRID + x, GRID + z, corner[0], corner[1]);
    return { ...e, position: [rx, y, rz], ...(e.yaw !== undefined ? { yaw: e.yaw + 90 * turns } : {}), roomTurns: turns };
  });

  const out = join(roomsDir, pool, `${paRoom}.json`);
  const entitiesOut = join(roomsDir, pool, `${paRoom}.entities.json`);
  const existing = index[key];
  if (entities.length && !dry) {
    mkdirSync(join(roomsDir, pool), { recursive: true });
    writeFileSync(entitiesOut, JSON.stringify({ source: "dungeondata", name: room.name, entities }));
  }
  if (existing && existing.source !== "dungeondata") {
    report.decorated.push(`${room.name} -> ${key}${entities.length ? ` (${entities.length} entities)` : ""}`);
    continue;
  }
  if (!dry) {
    mkdirSync(join(roomsDir, pool), { recursive: true });
    writeFileSync(out, JSON.stringify({
      room: paRoom, name: room.name, shape: pool, variant: null, facing, clean: false, floor: null,
      missRate: result.rate, capturedAt: new Date().toISOString(), source: "dungeondata",
      cells, origin: [relMinX, bottom, relMinZ], size: [relSizeX, outSizeY, relSizeZ], order: "yzx",
      palette, data, blockEntities: [], legacyBlockEntities,
    }));
    index[key] = { clean: false, floor: null, capturedAt: new Date().toISOString(), missRate: result.rate, source: "dungeondata" };
  }
  report.imported.push(`${room.name} -> ${key} ${facing} ${(100 * result.rate).toFixed(2)}%`);
}

if (!dry) writeFileSync(indexFile, JSON.stringify(Object.fromEntries(Object.entries(index).sort()), null, 2));

console.log(`Imported ${report.imported.length}:\n  ${report.imported.join("\n  ")}`);
console.log(`\nAlready captured, added decorations only ${report.decorated.length}:\n  ${report.decorated.join("\n  ")}`);
console.log(`\nNo match in PA's room data ${report.unmatched.length}:\n  ${report.unmatched.join("\n  ")}`);
