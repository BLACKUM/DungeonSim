import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { gzipSync } from "node:zlib";
import { call } from "./bridge.mjs";

const [roomsDir, doorsFile] = process.argv.slice(2);
if (!doorsFile) {
  console.error("usage: upload-rooms.mjs <PA rooms dir> <doors.json>");
  process.exit(1);
}

const POOLS = ["1x1", "1x2", "1x3", "1x4", "2x2", "l-shape", "puzzle", "trap", "miniboss", "special"];

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out.push(...walk(full));
    else if (name.endsWith(".json") && !name.endsWith(".entities.json")) out.push(full);
  }
  return out;
}

const uploads = [{ path: "doors.json", file: doorsFile }];
for (const pool of POOLS) {
  for (const file of walk(join(roomsDir, pool))) {
    uploads.push({ path: "rooms/" + relative(roomsDir, file).split(sep).join("/"), file });
  }
}

let done = 0;
let bytes = 0;
async function send({ path, file }) {
  const raw = readFileSync(file);
  bytes += raw.length;
  await call("/files/put", { path, gzip: gzipSync(raw).toString("base64") });
  done++;
  if (done % 25 === 0 || done === uploads.length) console.log(`${done}/${uploads.length}`);
}

const queue = [...uploads];
await Promise.all(Array.from({ length: 4 }, async () => {
  while (queue.length) await send(queue.shift());
}));
console.log(`Uploaded ${uploads.length} files (${(bytes / 1e6).toFixed(1)} MB before gzip). Run /ds reload on the server.`);
