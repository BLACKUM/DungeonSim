import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";
import { call } from "./bridge.mjs";

const jar = join(dirname(fileURLToPath(import.meta.url)), "..", "plugin", "build", "libs", "Bloodcamp.jar");
const result = await call("/plugin/stage", { gzip: gzipSync(readFileSync(jar)).toString("base64") });
console.log(`Staged ${result.bytes} bytes at ${result.staged}. Restart the server to load it.`);
