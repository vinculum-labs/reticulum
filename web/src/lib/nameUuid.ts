/**
 * Java's UUID.nameUUIDFromBytes (a version 3, MD5-based UUID) for UTF-8 text: how the server names the nodes and wires
 * of a blueprint pasted in as a copy ("<copy node id>/<blueprint node id>"), so the editor can find their activity.
 */
export function nameUuid(text: string): string {
  const hash = md5(new TextEncoder().encode(text));
  hash[6] = (hash[6] & 0x0f) | 0x30;
  hash[8] = (hash[8] & 0x3f) | 0x80;
  const hex = [...hash].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

const sides = new Map<string, string>();

/** The server's id for one input or output of a device node ("<node>:in:<n>"), which has its own activity. */
export function sideId(node: string, direction: "in" | "out", index: number): string {
  const key = `${node}:${direction}:${index}`;
  let id = sides.get(key);
  if (!id) sides.set(key, (id = nameUuid(key)));
  return id;
}

const SHIFT = [7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21];
const K = Array.from({ length: 64 }, (_, i) => Math.floor(Math.abs(Math.sin(i + 1)) * 2 ** 32) >>> 0);

/** MD5 (RFC 1321) of some bytes. */
function md5(input: Uint8Array): Uint8Array {
  const length = input.length;
  const padded = new Uint8Array(((length + 8) >>> 6) * 64 + 64);
  padded.set(input);
  padded[length] = 0x80;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 8, (length * 8) >>> 0, true);
  view.setUint32(padded.length - 4, Math.floor((length * 8) / 2 ** 32), true);
  let a0 = 0x67452301;
  let b0 = 0xefcdab89;
  let c0 = 0x98badcfe;
  let d0 = 0x10325476;
  for (let chunk = 0; chunk < padded.length; chunk += 64) {
    const m = Array.from({ length: 16 }, (_, i) => view.getUint32(chunk + i * 4, true));
    let a = a0;
    let b = b0;
    let c = c0;
    let d = d0;
    for (let i = 0; i < 64; i++) {
      let f: number;
      let g: number;
      if (i < 16) {
        f = (b & c) | (~b & d);
        g = i;
      } else if (i < 32) {
        f = (d & b) | (~d & c);
        g = (5 * i + 1) % 16;
      } else if (i < 48) {
        f = b ^ c ^ d;
        g = (3 * i + 5) % 16;
      } else {
        f = c ^ (b | ~d);
        g = (7 * i) % 16;
      }
      const sum = (a + f + K[i] + m[g]) >>> 0;
      a = d;
      d = c;
      c = b;
      b = (b + ((sum << SHIFT[i]) | (sum >>> (32 - SHIFT[i])))) >>> 0;
    }
    a0 = (a0 + a) >>> 0;
    b0 = (b0 + b) >>> 0;
    c0 = (c0 + c) >>> 0;
    d0 = (d0 + d) >>> 0;
  }
  const out = new Uint8Array(16);
  const outView = new DataView(out.buffer);
  [a0, b0, c0, d0].forEach((v, i) => outView.setUint32(i * 4, v, true));
  return out;
}
