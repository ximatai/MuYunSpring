import { sha256 } from '@noble/hashes/sha2.js';

/** UTF-8 digest shared with server source versions; works without WebCrypto on LAN HTTP. */
export function sha256Hex(text: string): string {
  return Array.from(sha256(new TextEncoder().encode(text)), (byte) =>
    byte.toString(16).padStart(2, '0'),
  ).join('');
}
