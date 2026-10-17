import { createHmac, randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

function readEnv(file) {
  const config = {};
  try {
    for (const line of readFileSync(file, "utf8").split(/\r?\n/)) {
      const match = line.match(/^\s*([A-Z_]+)\s*=\s*(.*?)\s*$/);
      if (match) config[match[1]] = match[2].replace(/^["']|["']$/g, "");
    }
  } catch {
  }
  return config;
}

const config = { ...readEnv(join(here, "..", "mcp", ".env")), ...readEnv(join(here, ".env")) };
const url = process.env.DS_BRIDGE_URL ?? config.DS_BRIDGE_URL;
const secret = process.env.DS_BRIDGE_SECRET ?? config.DS_BRIDGE_SECRET;

export function configured() {
  return Boolean(url && secret);
}

export function sign(key, time, nonce, method, path, body) {
  return createHmac("sha256", Buffer.from(key, "utf8"))
    .update(`${time}\n${nonce}\n${method}\n${path}\n`)
    .update(body)
    .digest("hex");
}

export async function call(path, payload = {}) {
  if (!configured()) {
    throw new Error("Bridge not configured. Put DS_BRIDGE_URL and DS_BRIDGE_SECRET in tools/.env (or set them as env vars)");
  }
  const body = Buffer.from(JSON.stringify(payload), "utf8");
  const time = Date.now().toString();
  const nonce = randomBytes(16).toString("hex");
  const target = new URL(path, url);

  let response;
  try {
    response = await fetch(target, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-DS-Time": time,
        "X-DS-Nonce": nonce,
        "X-DS-Signature": sign(secret, time, nonce, "POST", target.pathname, body),
      },
      body,
      signal: AbortSignal.timeout(60_000),
    });
  } catch (error) {
    throw new Error(`Can't reach the bridge at ${target.origin}: ${error.cause?.code ?? error.message}`);
  }

  const text = await response.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new Error(`Bridge returned HTTP ${response.status}: ${text.slice(0, 300)}`);
  }
  if (!json.ok) throw new Error(`Bridge error (HTTP ${response.status}): ${json.error}`);
  delete json.ok;
  return json;
}
