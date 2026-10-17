import { mkdirSync, readFileSync, readdirSync, writeFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

const args = process.argv.slice(2);
const dry = args.includes("--dry");
const recordDir = args.find((a) => !a.startsWith("--"));
if (!recordDir) {
  console.error("usage: import-records.mjs <PA record dir> [--dry]");
  process.exit(1);
}
const cache = join(dirname(fileURLToPath(import.meta.url)), "..", "data-cache", "records");

const files = (kind) => {
  const dir = join(recordDir, kind);
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap((shape) =>
    readdirSync(join(dir, shape)).filter((f) => f.endsWith(".json")).map((f) => ({ shape, file: join(dir, shape, f) })));
};
const plain = (s) => (s ?? "").replace(/§./g, "");
const hasHearts = (s) => typeof s === "string" && s.includes("❤");

function label(mob) {
  const options = [mob.name, mob.lastName].filter(hasHearts);
  const fels = (s) => /\bFels\b/.test(plain(s));
  if (mob.type === "minecraft:enderman") return options.find(fels) ?? "§c§lFels";
  return options.find((s) => !fels(s)) ?? null;
}

function cleanMob(mob) {
  const type = mob.type;
  if (type === "minecraft:bat") {
    if (mob.invisibleAtFirst || mob.maxHealth !== 100) return null;
    return base(mob, "§7Secret Bat");
  }
  if (type === "minecraft:player") {
    if (mob.maxHealth < 100) return null;
    return base(mob, label(mob));
  }
  const name = label(mob);
  if (!name && type !== "minecraft:enderman") return null;
  return base(mob, name);
}

function base(mob, name) {
  const out = { type: mob.type, name, pos: mob.pos, yaw: mob.yaw ?? 0, invisible: !!mob.invisibleAtFirst };
  if (mob.starred) out.starred = true;
  if (mob.skin) out.skin = mob.skin;
  if (mob.equipment && Object.keys(mob.equipment).length) out.equipment = mob.equipment;
  if (typeof mob.firstSeenAfterEnteringMs === "number") out.seenAfterEnteringMs = mob.firstSeenAfterEnteringMs;
  if (typeof mob.playerDistanceAtFirstSeen === "number") out.playerDistance = mob.playerDistanceAtFirstSeen;
  return out;
}

function importMobs({ shape, file }) {
  const json = JSON.parse(readFileSync(file, "utf8"));
  const sessions = [...json.sessions].sort((a, b) => (a.clearedBeforeEntering ? 1 : 0) - (b.clearedBeforeEntering ? 1 : 0) || b.mobs.length - a.mobs.length);
  const session = sessions[0];
  const kept = session.mobs.map(cleanMob).filter(Boolean);
  const dropped = session.mobs.length - kept.length;
  return {
    path: `records/mobs/${shape}/${json.room}.json`,
    report: `${json.name} (${shape}): ${kept.length} mobs kept, ${dropped} dropped, ${json.sessions.length} session(s)${session.clearedBeforeEntering ? ", room was already cleared" : ""}`,
    data: { room: json.room, name: json.name, shape, facing: session.facing, recordedAt: session.recordedAt, sessions: json.sessions.length, mobs: kept, keys: session.keys ?? [] },
  };
}

const NEVER = /cracked_stone_bricks|chest|player_head|player_wall_head|lever|stone_button|_door/;

function clusters(changes) {
  const key = (p) => p.join(",");
  const byPos = new Map(changes.map((c) => [key(c.pos), c]));
  const seen = new Set();
  const out = [];
  for (const c of changes) {
    if (seen.has(key(c.pos))) continue;
    const group = [];
    const queue = [c.pos];
    seen.add(key(c.pos));
    while (queue.length) {
      const p = queue.pop();
      group.push(byPos.get(key(p)));
      for (const [dx, dy, dz] of [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]]) {
        const n = [p[0] + dx, p[1] + dy, p[2] + dz];
        if (byPos.has(key(n)) && !seen.has(key(n))) {
          seen.add(key(n));
          queue.push(n);
        }
      }
    }
    out.push(group);
  }
  return out;
}

function passage(changes) {
  const removed = changes.filter((c) => !c.from.startsWith("minecraft:air") && c.to.startsWith("minecraft:air") && !NEVER.test(c.from));
  return clusters(removed).filter((g) => g.length >= 1).flat();
}

function importLevers({ shape, file }) {
  const json = JSON.parse(readFileSync(file, "utf8"));
  const levers = [];
  const notes = [];
  const byLever = new Map();
  for (const [at, lever] of Object.entries(json.levers ?? {})) {
    for (const r of lever.recordings ?? [lever]) {
      const clicked = (r.clicked ?? at.split(",").map(Number)).join(",");
      if (!byLever.has(clicked)) byLever.set(clicked, { secretName: lever.secretName ?? null, recordings: [] });
      byLever.get(clicked).recordings.push(r);
    }
  }
  for (const [at, lever] of byLever) {
    const recordings = lever.recordings;
    const sets = recordings.map((r) => passage(r.changes ?? []));
    const count = new Map();
    const block = new Map();
    for (const set of sets) {
      for (const c of set) {
        const k = c.pos.join(",");
        count.set(k, (count.get(k) ?? 0) + 1);
        block.set(k, c);
      }
    }
    const need = sets.length >= 2 ? Math.max(2, Math.ceil(sets.length / 2)) : 1;
    const blocks = [...block.entries()].filter(([k]) => count.get(k) >= need).map(([, c]) => ({ pos: c.pos, from: c.from }));
    const raw = recordings.reduce((n, r) => n + (r.changes?.length ?? 0), 0);
    levers.push({ at: at.split(",").map(Number), secretName: lever.secretName ?? null, recordings: recordings.length, blocks });
    notes.push(`  ${lever.secretName ?? at}: ${blocks.length} blocks from ${recordings.length} recording(s) (${raw} raw changes)${recordings.length < 2 ? ", only one run so it may still have other stuff in it" : ""}`);
  }
  return {
    path: `records/levers/${shape}/${json.room}.json`,
    report: `${json.name} (${shape}) levers:\n${notes.join("\n")}`,
    data: { room: json.room, name: json.name, shape, levers },
  };
}

const imports = [...files("mobs").map(importMobs), ...files("levers").map(importLevers)];
for (const i of imports) {
  console.log(i.report);
  const local = join(cache, i.path.replace(/^records\//, ""));
  mkdirSync(dirname(local), { recursive: true });
  writeFileSync(local, JSON.stringify(i.data, null, 1));
}

if (!dry) {
  const { call } = await import("./bridge.mjs");
  for (const i of imports) {
    await call("/files/put", { path: i.path, gzip: gzipSync(Buffer.from(JSON.stringify(i.data))).toString("base64") });
  }
  console.log(`Uploaded ${imports.length} files. Run /ds reload on the server.`);
} else {
  console.log(`Dry run: ${imports.length} files written to data-cache/records, nothing uploaded.`);
}
