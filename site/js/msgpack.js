// Minimal MessagePack decoder (spec: github.com/msgpack/msgpack/blob/master/spec.md).
// Covers everything Python's msgpack.packb emits: nil, bool, ints, float32/64, str, bin, array, map.
// Ext types are not used by the bundle and throw.

const td = new TextDecoder();

export function decode(bytes) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  let pos = 0;

  const str = (n) => { const s = td.decode(u8.subarray(pos, pos + n)); pos += n; return s; };
  const bin = (n) => { const b = u8.slice(pos, pos + n); pos += n; return b; };
  const arr = (n) => { const a = new Array(n); for (let i = 0; i < n; i++) a[i] = read(); return a; };
  const map = (n) => { const o = {}; for (let i = 0; i < n; i++) { const k = read(); o[k] = read(); } return o; };
  const u16 = () => { const v = dv.getUint16(pos); pos += 2; return v; };
  const u32 = () => { const v = dv.getUint32(pos); pos += 4; return v; };

  function read() {
    const t = u8[pos++];
    if (t <= 0x7f) return t;                     // positive fixint
    if (t >= 0xe0) return t - 0x100;             // negative fixint
    if ((t & 0xf0) === 0x80) return map(t & 0x0f);
    if ((t & 0xf0) === 0x90) return arr(t & 0x0f);
    if ((t & 0xe0) === 0xa0) return str(t & 0x1f);
    switch (t) {
      case 0xc0: return null;
      case 0xc2: return false;
      case 0xc3: return true;
      case 0xc4: return bin(u8[pos++]);
      case 0xc5: return bin(u16());
      case 0xc6: return bin(u32());
      case 0xca: { const v = dv.getFloat32(pos); pos += 4; return v; }
      case 0xcb: { const v = dv.getFloat64(pos); pos += 8; return v; }
      case 0xcc: return u8[pos++];
      case 0xcd: return u16();
      case 0xce: return u32();
      case 0xcf: { const v = dv.getBigUint64(pos); pos += 8; return Number(v); }
      case 0xd0: { const v = dv.getInt8(pos); pos += 1; return v; }
      case 0xd1: { const v = dv.getInt16(pos); pos += 2; return v; }
      case 0xd2: { const v = dv.getInt32(pos); pos += 4; return v; }
      case 0xd3: { const v = dv.getBigInt64(pos); pos += 8; return Number(v); }
      case 0xd9: return str(u8[pos++]);
      case 0xda: return str(u16());
      case 0xdb: return str(u32());
      case 0xdc: return arr(u16());
      case 0xdd: return arr(u32());
      case 0xde: return map(u16());
      case 0xdf: return map(u32());
      default: throw new Error(`msgpack: unsupported type 0x${t.toString(16)} at ${pos - 1}`);
    }
  }

  const value = read();
  if (pos !== u8.length) throw new Error(`msgpack: ${u8.length - pos} trailing bytes`);
  return value;
}
