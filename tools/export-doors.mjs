import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const [ddDir, legacyFile, outFile] = process.argv.slice(2);
if (!outFile) {
  console.error("usage: export-doors.mjs <DungeonData dir> <legacy.json> <out file>");
  process.exit(1);
}

const legacy = JSON.parse(readFileSync(legacyFile, "utf8")).blocks;
const lines = readFileSync(join(ddDir, "door_data", "doors.txt"), "utf8").split(/\r?\n/).filter((l) => l.length >= 500);

function modern(state) {
  if (!/_wall\[/.test(state)) return state;
  return state
    .replace(/(north|south|east|west)=false/g, "$1=none")
    .replace(/(north|south|east|west)=true/g, "$1=low")
    .replace("up=false", "up=true");
}

const frames = lines.map((line) => {
  const out = [];
  for (let i = 0; i < 125; i++) {
    const raw = parseInt(line.substr(i * 4, 4), 16);
    out.push(modern(legacy[`${raw >> 4}:${raw & 15}`] ?? "minecraft:air"));
  }
  return out;
});

writeFileSync(outFile, JSON.stringify({ frames }));
console.log(`Wrote ${frames.length} door frames to ${outFile}`);
