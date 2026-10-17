import { readFileSync } from "node:fs";
import { gunzipSync } from "node:zlib";

export function readNbtTyped(file) {
  let b = readFileSync(file);
  if (b[0] === 0x1f && b[1] === 0x8b) b = gunzipSync(b);
  let p = 0;
  const i32 = () => { const v = b.readInt32BE(p); p += 4; return v; };
  const str = () => { const n = b.readUInt16BE(p); p += 2; const s = b.toString("utf8", p, p + n); p += n; return s; };
  const payload = (t) => {
    switch (t) {
      case 1: return b.readInt8(p++);
      case 2: { const v = b.readInt16BE(p); p += 2; return v; }
      case 3: return i32();
      case 4: { const v = b.readBigInt64BE(p); p += 8; return v; }
      case 5: { const v = b.readFloatBE(p); p += 4; return v; }
      case 6: { const v = b.readDoubleBE(p); p += 8; return v; }
      case 7: { const n = i32(); const v = b.subarray(p, p + n); p += n; return v; }
      case 8: return str();
      case 9: { const et = b[p++], n = i32(); const a = []; for (let i = 0; i < n; i++) a.push({ type: et, value: payload(et) }); return a; }
      case 10: { const o = {}; for (;;) { const tt = b[p++]; if (tt === 0) return o; const k = str(); o[k] = { type: tt, value: payload(tt) }; } }
      case 11: { const n = i32(); const a = []; for (let i = 0; i < n; i++) a.push(i32()); return a; }
      case 12: { const n = i32(); const a = []; for (let i = 0; i < n; i++) { a.push(b.readBigInt64BE(p)); p += 8; } return a; }
      default: throw new Error(`bad NBT tag ${t} at ${p} in ${file}`);
    }
  };
  const t = b[p++];
  str();
  return { type: t, value: payload(t) };
}

export function plain(node) {
  const { type, value } = node;
  if (type === 10) return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, plain(v)]));
  if (type === 9) return value.map(plain);
  return value;
}

const SNBT_KEY = /^[A-Za-z0-9._+-]+$/;
const quote = (s) => `"${s.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;

export function snbt(node) {
  const { type, value } = node;
  switch (type) {
    case 1: return `${value}b`;
    case 2: return `${value}s`;
    case 3: return `${value}`;
    case 4: return `${value}L`;
    case 5: return `${value}f`;
    case 6: return `${value}d`;
    case 7: return `[B;${[...value].map((v) => `${(v << 24) >> 24}b`).join(",")}]`;
    case 8: return quote(value);
    case 9: return `[${value.map(snbt).join(",")}]`;
    case 10: return `{${Object.entries(value).map(([k, v]) => `${SNBT_KEY.test(k) ? k : quote(k)}:${snbt(v)}`).join(",")}}`;
    case 11: return `[I;${value.join(",")}]`;
    case 12: return `[L;${value.map((v) => `${v}L`).join(",")}]`;
  }
  throw new Error(`bad NBT tag ${type}`);
}

export function readSchem(file) {
  let root = readNbtTyped(file).value;
  if (root.Schematic) root = root.Schematic.value;
  const blocks = root.Blocks ? root.Blocks.value : root;
  const palette = [];
  for (const [state, id] of Object.entries(blocks.Palette.value)) palette[id.value] = state;
  const raw = (blocks.Data ?? root.BlockData).value;
  const w = root.Width.value, h = root.Height.value, l = root.Length.value;
  const data = new Int32Array(w * h * l);
  for (let i = 0, p = 0; i < data.length; i++) {
    let v = 0, shift = 0, c;
    do { c = raw[p++]; v |= (c & 127) << shift; shift += 7; } while (c & 128);
    data[i] = v;
  }
  const entities = (blocks.BlockEntities ?? root.BlockEntities)?.value ?? [];
  const blockEntities = entities.map((e) => {
    const fields = e.value;
    const data = fields.Data ? fields.Data.value : Object.fromEntries(Object.entries(fields).filter(([k]) => k !== "Pos" && k !== "Id"));
    return { pos: fields.Pos.value, id: fields.Id?.value, data: { type: 10, value: data } };
  });
  return { w, h, l, palette, data, blockEntities };
}
