/**
 * Golden test-vector generator for the vault-kotlin crypto core (Phase 1).
 *
 * Runs the reference TypeScript/JS implementations (seedify-ts + nostr-tools,
 * as wired in the ../vault app) and emits a deterministic JSON fixture that the
 * Kotlin parity tests assert against byte-for-byte.
 *
 * Chain reproduced (see architecture-overview.md §4):
 *   SSH key (+passphrase) -> raw 32-byte ed25519 seed
 *                         -> Seedify.toMnemonic(24)   (BIP39, no-prefix)
 *   mnemonic              -> NIP-06 m/44'/1237'/0'/0/0 (secp256k1) npub/nsec
 *   pubkey blob           -> SHA256 fingerprint (ssh-keygen -lf parity)
 *   NIP-44 v2             -> conversation-key vector (self-encryption)
 *
 * Run:  node tools/gen-golden-vectors.mjs
 * Out:  app/src/test/resources/golden/crypto-vectors.json
 */

import { createRequire } from 'node:module';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, '..');
const vaultDir = resolve(repoRoot, '../vault');
const seedifyDir = resolve(repoRoot, '../seedify-ts');

// Resolve reference deps from the vault app's node_modules.
const require = createRequire(resolve(vaultDir, 'package.json'));
const { nip44, nip19, utils } = require('nostr-tools');
const { accountFromSeedWords } = require('nostr-tools/nip06');
const seedify = require(resolve(seedifyDir, 'dist/index.js'));
const { sha256 } = require('@noble/hashes/sha2.js');

const {
  Seedify,
  parseOpenSSHEd25519PrivateKey,
} = seedify;

const bytesToHex = utils.bytesToHex;

// --- Reference SSH keys (same fixtures used by seedify-ts test suite) --------

const UNENCRYPTED_ED25519 = `-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACDJQSoksziSwoefzN4O7jzRv6vJpyOW9CnGXgkUjVajHAAAAIgvWUstL1lL
LQAAAAtzc2gtZWQyNTUxOQAAACDJQSoksziSwoefzN4O7jzRv6vJpyOW9CnGXgkUjVajHA
AAAEAHa+hzPanJO/Ef7G03pLLub2KNYh+szqeY/1e7ADjdy8lBKiSzOJLCh5/M3g7uPNG/
q8mnI5b0KcZeCRSNVqMcAAAAAAECAwQF
-----END OPENSSH PRIVATE KEY-----`;

// Encrypted with aes256-ctr + bcrypt; passphrase is unknown here so it is only
// used to prove "wrong/missing passphrase throws". The Kotlin suite generates
// its own encrypted keys with a known passphrase + 1-round KDF for decrypt.
const ENCRYPTED_ED25519 = `-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABBmspXicJ
4jGegEfhH7XJL4AAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIEB88EAE2ZbegKkK
QHkWryJW725cojU6GJ8rAMjx7lKQAAAAoMr2Uq9ybH6oQGlZiiiUd0xDrPjTRtoxkTUVTC
GYlCzWodcB0N8w+AzvHpYhjRridHK+QwJoaooJXNYcNF+amSoJm8BoPPnqPF1f5GBF/cc3
IUQzBJEmktr0aTXUMVXKJffCejcHUpDR4QVfaSU3Sczuj3cv/iG+TWzjnw4ouOTdIWr/f8
ttX/inpncyZFV0Ddvm/IvIdd+ldRkskiV51Ko=
-----END OPENSSH PRIVATE KEY-----`;

function sshString(buf) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(buf.length, 0);
  return Buffer.concat([len, buf]);
}

function fingerprintFromPubkey(pubkeyRaw) {
  const blob = Buffer.concat([
    sshString(Buffer.from('ssh-ed25519')),
    sshString(Buffer.from(pubkeyRaw)),
  ]);
  const digest = sha256(new Uint8Array(blob));
  const b64 = Buffer.from(digest).toString('base64').replace(/=+$/, '');
  return `SHA256:${b64}`;
}

async function buildUnencryptedVector() {
  const seed = parseOpenSSHEd25519PrivateKey(UNENCRYPTED_ED25519); // Uint8Array(32)
  const mnemonic24 = await Seedify(UNENCRYPTED_ED25519).toMnemonic(24);
  const mnemonic12 = await Seedify(UNENCRYPTED_ED25519).toMnemonic(12);

  // Production Nostr path: NIP-06 from the 24-word mnemonic (secp256k1).
  const acct = accountFromSeedWords(mnemonic24);
  const nostrNip06 = {
    pubkey: acct.publicKey,
    privkey: bytesToHex(acct.privateKey),
    npub: nip19.npubEncode(acct.publicKey),
    nsec: nip19.nsecEncode(acct.privateKey),
  };

  // ed25519 pubkey (from the fixture's inner blob) for the SSH fingerprint.
  // The fixture's raw pubkey is the 32 bytes after "ssh-ed25519" in the blob:
  // c9412a24b33892c2879fccde0eee3cd1bfabc9a72396f429c65e09148d56a31c
  const pubkeyRawHex =
    'c9412a24b33892c2879fccde0eee3cd1bfabc9a72396f429c65e09148d56a31c';
  const fingerprint = fingerprintFromPubkey(Buffer.from(pubkeyRawHex, 'hex'));

  return {
    label: 'unencrypted-ed25519',
    sshKeyPem: UNENCRYPTED_ED25519,
    passphrase: null,
    seedHex: bytesToHex(seed),
    ed25519PubkeyHex: pubkeyRawHex,
    sshFingerprint: fingerprint,
    mnemonic24,
    mnemonic12,
    nostrNip06,
  };
}

function buildNip44Vector() {
  // Deterministic NIP-44 v2 conversation-key + message-key vector using a
  // fixed secret. This is a *stable* target (no random nonce involved).
  // Values below are computed live from nostr-tools so they always match the
  // shipped library, and the Kotlin Nip44 impl must reproduce them.
  const { getPublicKey } = require('nostr-tools');
  const sk1 = '0000000000000000000000000000000000000000000000000000000000000001';
  const sk2 = '0000000000000000000000000000000000000000000000000000000000000002';
  const pub2 = getPublicKey(utils.hexToBytes(sk2)); // hex string
  const convKey = nip44.v2.utils.getConversationKey(
    utils.hexToBytes(sk1),
    pub2
  );

  // Self-encryption round-trip target (sender == recipient == Vault Key).
  const selfSk = '00000000000000000000000000000000000000000000000000000000deadbeef';
  const selfPub = getPublicKey(utils.hexToBytes(selfSk));
  const selfConv = nip44.v2.utils.getConversationKey(
    utils.hexToBytes(selfSk),
    selfPub
  );
  const plaintext = '{"hello":"vault"}';
  const ciphertext = nip44.v2.encrypt(plaintext, selfConv);
  const roundtrip = nip44.v2.decrypt(ciphertext, selfConv);
  if (roundtrip !== plaintext) throw new Error('nip44 self roundtrip failed');

  return {
    note:
      'NIP-44 v2. conversationKey is deterministic; encrypt uses a random ' +
      'nonce so ciphertext is NOT a golden — assert decrypt(ciphertext)==plaintext ' +
      'and reproduce conversationKey exactly. Use the official NIP-44 vectors ' +
      '(nip44.vectors.json) for full ChaCha20 parity.',
    conversationKey_sk1_pub2: bytesToHex(convKey),
    self: {
      privkey: selfSk,
      pubkey: selfPub,
      conversationKey: bytesToHex(selfConv),
      samplePlaintext: plaintext,
      sampleCiphertext: ciphertext,
    },
  };
}

async function main() {
  const unenc = await buildUnencryptedVector();
  const nip44Vec = buildNip44Vector();

  const out = {
    _generatedBy: 'tools/gen-golden-vectors.mjs',
    _sources: {
      seedify: 'seedify-ts/dist',
      nostr: 'nostr-tools (via ../vault)',
    },
    _chain:
      "SSH+pass -> 32B seed -> Seedify.toMnemonic(24) -> NIP-06 m/44'/1237'/0'/0/0 -> npub/nsec",
    keys: {
      unencryptedEd25519Pem: UNENCRYPTED_ED25519,
      encryptedEd25519Pem: ENCRYPTED_ED25519,
    },
    vectors: [unenc],
    nip44: nip44Vec,
  };

  const outPath = resolve(
    repoRoot,
    'app/src/test/resources/golden/crypto-vectors.json'
  );
  mkdirSync(dirname(outPath), { recursive: true });
  writeFileSync(outPath, JSON.stringify(out, null, 2) + '\n');
  console.log('Wrote', outPath);
  console.log(JSON.stringify(out, null, 2));
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
