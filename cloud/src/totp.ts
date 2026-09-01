/* RFC 6238 TOTP, dependency-free (SHA-1, 30 s steps, 6 digits — what every
   authenticator app speaks). Secrets are RFC 4648 base32. */
import { createHmac, randomBytes } from "node:crypto";

const ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

export const generateSecret = (): string => {
  const buf = randomBytes(20);
  let bits = 0, value = 0, out = "";
  for (const byte of buf) {
    value = (value << 8) | byte; bits += 8;
    while (bits >= 5) { out += ALPHA[(value >>> (bits - 5)) & 31]; bits -= 5; }
  }
  if (bits > 0) out += ALPHA[(value << (5 - bits)) & 31];
  return out;
};

const b32decode = (s: string): Buffer => {
  let bits = 0, value = 0; const out: number[] = [];
  for (const c of s.toUpperCase().replace(/=+$/, "")) {
    const idx = ALPHA.indexOf(c);
    if (idx === -1) continue;
    value = (value << 5) | idx; bits += 5;
    if (bits >= 8) { out.push((value >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return Buffer.from(out);
};

const hotp = (secret: string, counter: number): string => {
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(counter));
  const h = createHmac("sha1", b32decode(secret)).update(buf).digest();
  const o = h[h.length - 1] & 0xf;
  const num = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(num % 1_000_000).padStart(6, "0");
};

/* accept the current step and one either side (clock drift) */
export const totpCheck = (secret: string, code: string): boolean => {
  const c = code.replace(/\s+/g, "");
  if (!/^\d{6}$/.test(c)) return false;
  const step = Math.floor(Date.now() / 30_000);
  return [-1, 0, 1].some((w) => hotp(secret, step + w) === c);
};

export const otpauthUrl = (secret: string, account: string): string =>
  `otpauth://totp/${encodeURIComponent("RePaper Cloud")}:${encodeURIComponent(account)}?secret=${secret}&issuer=${encodeURIComponent("RePaper Cloud")}&algorithm=SHA1&digits=6&period=30`;
