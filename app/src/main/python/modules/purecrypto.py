# -*- coding: utf-8 -*-
"""Pure-Python AES-256-GCM and ChaCha20-Poly1305.

Embedded fallback so the addon works on Kodi installs that don't ship
``pycryptodome`` or ``cryptography`` (notably the bundled-Python flavour of
Kodi on Android). Slower than C implementations but fast enough for a few
small JSON envelopes per playback (single decryption < 50 ms on ARM).

Authoritative references:
  * AES (FIPS 197), GCM (NIST SP 800-38D), GHASH (Section 6.4 of the same)
  * ChaCha20 (RFC 7539), Poly1305 (RFC 7539)

Test vectors below are run automatically when this module is invoked as a
script — execute ``python -m resources.lib.purecrypto`` from the addon root.
"""
import struct


# ---------------------------------------------------------------------------
# AES (key schedule + block encrypt only — GCM uses encrypt for everything)
# ---------------------------------------------------------------------------

_SBOX = (
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
)

_RCON = (0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36, 0x6c, 0xd8, 0xab, 0x4d, 0x9a, 0x2f, 0x5e, 0xbc, 0x63, 0xc6, 0x97, 0x35, 0x6a, 0xd4)


def _xtime(b):
    return ((b << 1) ^ (0x1b if b & 0x80 else 0)) & 0xff


def _key_expansion(key):
    """AES-256 key schedule. Key is 32 bytes; output is 60 32-bit words = 15 round keys."""
    Nk, Nr = 8, 14
    w = [0] * (4 * (Nr + 1))
    for i in range(Nk):
        w[i] = struct.unpack('>I', key[4 * i:4 * (i + 1)])[0]
    for i in range(Nk, 4 * (Nr + 1)):
        t = w[i - 1]
        if i % Nk == 0:
            # RotWord + SubWord ^ Rcon
            t = ((t << 8) | (t >> 24)) & 0xffffffff
            t = ((_SBOX[(t >> 24) & 0xff] << 24) |
                 (_SBOX[(t >> 16) & 0xff] << 16) |
                 (_SBOX[(t >> 8) & 0xff] << 8) |
                 _SBOX[t & 0xff])
            t ^= _RCON[i // Nk - 1] << 24
        elif i % Nk == 4:
            t = ((_SBOX[(t >> 24) & 0xff] << 24) |
                 (_SBOX[(t >> 16) & 0xff] << 16) |
                 (_SBOX[(t >> 8) & 0xff] << 8) |
                 _SBOX[t & 0xff])
        w[i] = w[i - Nk] ^ t
    return w


def _aes256_encrypt_block(rk, block):
    """Encrypt a single 16-byte block with AES-256 round keys ``rk``."""
    Nr = 14
    s = [block[i] for i in range(16)]
    # Initial AddRoundKey
    for c in range(4):
        rkw = rk[c]
        s[c * 4 + 0] ^= (rkw >> 24) & 0xff
        s[c * 4 + 1] ^= (rkw >> 16) & 0xff
        s[c * 4 + 2] ^= (rkw >> 8) & 0xff
        s[c * 4 + 3] ^= rkw & 0xff
    for round_ in range(1, Nr):
        # SubBytes
        for i in range(16):
            s[i] = _SBOX[s[i]]
        # ShiftRows (state is column-major)
        s[1], s[5], s[9], s[13] = s[5], s[9], s[13], s[1]
        s[2], s[6], s[10], s[14] = s[10], s[14], s[2], s[6]
        s[3], s[7], s[11], s[15] = s[15], s[3], s[7], s[11]
        # MixColumns
        for c in range(4):
            o = c * 4
            a0, a1, a2, a3 = s[o], s[o + 1], s[o + 2], s[o + 3]
            t = a0 ^ a1 ^ a2 ^ a3
            s[o] ^= t ^ _xtime(a0 ^ a1)
            s[o + 1] ^= t ^ _xtime(a1 ^ a2)
            s[o + 2] ^= t ^ _xtime(a2 ^ a3)
            s[o + 3] ^= t ^ _xtime(a3 ^ a0)
        # AddRoundKey
        for c in range(4):
            rkw = rk[round_ * 4 + c]
            s[c * 4 + 0] ^= (rkw >> 24) & 0xff
            s[c * 4 + 1] ^= (rkw >> 16) & 0xff
            s[c * 4 + 2] ^= (rkw >> 8) & 0xff
            s[c * 4 + 3] ^= rkw & 0xff
    # Final round (no MixColumns)
    for i in range(16):
        s[i] = _SBOX[s[i]]
    s[1], s[5], s[9], s[13] = s[5], s[9], s[13], s[1]
    s[2], s[6], s[10], s[14] = s[10], s[14], s[2], s[6]
    s[3], s[7], s[11], s[15] = s[15], s[3], s[7], s[11]
    for c in range(4):
        rkw = rk[Nr * 4 + c]
        s[c * 4 + 0] ^= (rkw >> 24) & 0xff
        s[c * 4 + 1] ^= (rkw >> 16) & 0xff
        s[c * 4 + 2] ^= (rkw >> 8) & 0xff
        s[c * 4 + 3] ^= rkw & 0xff
    return bytes(s)


# ---------------------------------------------------------------------------
# GHASH (multiplication in GF(2^128) used by GCM)
# ---------------------------------------------------------------------------

def _gf_mul(x, y):
    R = 0xe1 << 120
    z = 0
    v = y
    for i in range(127, -1, -1):
        if (x >> i) & 1:
            z ^= v
        if v & 1:
            v = (v >> 1) ^ R
        else:
            v >>= 1
    return z


def _ghash(h_int, aad, ct):
    def _pad16(b):
        return b + b'\x00' * ((16 - len(b) % 16) % 16)
    data = _pad16(aad) + _pad16(ct) + struct.pack('>QQ', len(aad) * 8, len(ct) * 8)
    y = 0
    for i in range(0, len(data), 16):
        block = int.from_bytes(data[i:i + 16], 'big')
        y = _gf_mul(y ^ block, h_int)
    return y.to_bytes(16, 'big')


# ---------------------------------------------------------------------------
# AES-256-GCM (decrypt + authenticate)
# ---------------------------------------------------------------------------

def aes256_gcm_decrypt(key, iv, ciphertext, tag, aad=b''):
    """Decrypt and verify AES-256-GCM. Raises ValueError on auth failure.

    key: 32 bytes
    iv: 12 bytes (we don't support arbitrary IV lengths — Stigstream uses 12)
    ciphertext: bytes
    tag: 16 bytes
    """
    if len(key) != 32:
        raise ValueError('AES-256-GCM key must be 32 bytes')
    if len(iv) != 12:
        raise ValueError('AES-256-GCM IV must be 12 bytes (this implementation)')
    if len(tag) != 16:
        raise ValueError('AES-256-GCM tag must be 16 bytes')
    rk = _key_expansion(key)
    H = _aes256_encrypt_block(rk, b'\x00' * 16)
    h_int = int.from_bytes(H, 'big')
    j0 = iv + b'\x00\x00\x00\x01'
    # Decrypt CTR (counter starts at j0+1)
    pt = bytearray()
    for i in range(0, len(ciphertext), 16):
        ctr_int = int.from_bytes(j0, 'big') + 1 + (i // 16)
        # Wrap on the low 32 bits per GCM
        ctr_int = (int.from_bytes(j0[:12], 'big') << 32) | ((ctr_int & 0xffffffff))
        ctr = ctr_int.to_bytes(16, 'big')
        ks = _aes256_encrypt_block(rk, ctr)
        chunk = ciphertext[i:i + 16]
        pt.extend(c ^ k for c, k in zip(chunk, ks[:len(chunk)]))
    # Compute expected tag
    s = _ghash(h_int, aad, ciphertext)
    e_j0 = _aes256_encrypt_block(rk, j0)
    expected = bytes(a ^ b for a, b in zip(s, e_j0))
    # Constant-time-ish compare
    diff = 0
    for a, b in zip(expected, tag):
        diff |= a ^ b
    if diff != 0:
        raise ValueError('AES-256-GCM: authentication tag mismatch')
    return bytes(pt)


# ---------------------------------------------------------------------------
# ChaCha20 + Poly1305 (RFC 7539)
# ---------------------------------------------------------------------------

def _rotl32(x, n):
    return ((x << n) & 0xffffffff) | (x >> (32 - n))


def _chacha20_block(key, counter, nonce):
    """Generate a single 64-byte ChaCha20 keystream block."""
    constants = (0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)
    state = list(constants)
    state += list(struct.unpack('<8I', key))
    state.append(counter)
    state += list(struct.unpack('<3I', nonce))
    working = list(state)

    def qr(s, a, b, c, d):
        s[a] = (s[a] + s[b]) & 0xffffffff; s[d] = _rotl32(s[d] ^ s[a], 16)
        s[c] = (s[c] + s[d]) & 0xffffffff; s[b] = _rotl32(s[b] ^ s[c], 12)
        s[a] = (s[a] + s[b]) & 0xffffffff; s[d] = _rotl32(s[d] ^ s[a], 8)
        s[c] = (s[c] + s[d]) & 0xffffffff; s[b] = _rotl32(s[b] ^ s[c], 7)

    for _ in range(10):
        qr(working, 0, 4, 8, 12)
        qr(working, 1, 5, 9, 13)
        qr(working, 2, 6, 10, 14)
        qr(working, 3, 7, 11, 15)
        qr(working, 0, 5, 10, 15)
        qr(working, 1, 6, 11, 12)
        qr(working, 2, 7, 8, 13)
        qr(working, 3, 4, 9, 14)
    out = b''
    for i in range(16):
        out += struct.pack('<I', (working[i] + state[i]) & 0xffffffff)
    return out


def _chacha20_xor(key, nonce, counter, data):
    out = bytearray()
    for i in range(0, len(data), 64):
        ks = _chacha20_block(key, counter + i // 64, nonce)
        for j, b in enumerate(data[i:i + 64]):
            out.append(b ^ ks[j])
    return bytes(out)


def _poly1305_mac(msg, key):
    """Compute Poly1305 MAC. key is 32 bytes (16 r + 16 s)."""
    r = int.from_bytes(key[:16], 'little')
    r &= 0x0ffffffc0ffffffc0ffffffc0fffffff
    s = int.from_bytes(key[16:32], 'little')
    p = (1 << 130) - 5
    acc = 0
    for i in range(0, len(msg), 16):
        block = msg[i:i + 16]
        n = int.from_bytes(block + b'\x01' + b'\x00' * (16 - len(block)), 'little')
        acc = ((acc + n) * r) % p
    acc = (acc + s) & ((1 << 128) - 1)
    return acc.to_bytes(16, 'little')


def chacha20_poly1305_decrypt(key, nonce, ciphertext, tag, aad=b''):
    """RFC 7539 AEAD decrypt + verify."""
    if len(key) != 32:
        raise ValueError('ChaCha20-Poly1305 key must be 32 bytes')
    if len(nonce) != 12:
        raise ValueError('ChaCha20-Poly1305 nonce must be 12 bytes')
    if len(tag) != 16:
        raise ValueError('ChaCha20-Poly1305 tag must be 16 bytes')
    poly_key = _chacha20_block(key, 0, nonce)[:32]

    def _pad16(b):
        return b + b'\x00' * ((16 - len(b) % 16) % 16)

    auth_msg = (_pad16(aad) + _pad16(ciphertext)
                + struct.pack('<Q', len(aad)) + struct.pack('<Q', len(ciphertext)))
    expected = _poly1305_mac(auth_msg, poly_key)
    diff = 0
    for a, b in zip(expected, tag):
        diff |= a ^ b
    if diff != 0:
        raise ValueError('ChaCha20-Poly1305: authentication tag mismatch')
    return _chacha20_xor(key, nonce, 1, ciphertext)


# ---------------------------------------------------------------------------
# Quick self-test (run when invoked as a script)
# ---------------------------------------------------------------------------

def _self_test():
    import os
    # RFC 7539 ChaCha20-Poly1305 test vector
    key = bytes(range(0x80, 0x80 + 32))
    nonce = bytes.fromhex('070000004041424344454647')
    aad = bytes.fromhex('50515253c0c1c2c3c4c5c6c7')
    pt = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
    ct_expected = bytes.fromhex(
        'd31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6'
        '3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36'
        '92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc'
        '3ff4def08e4b7a9de576d26586cec64b6116'
    )
    tag_expected = bytes.fromhex('1ae10b594f09e26a7e902ecbd0600691')
    pt_back = chacha20_poly1305_decrypt(key, nonce, ct_expected, tag_expected, aad)
    assert pt_back == pt, 'ChaCha20-Poly1305 decrypt vector failed'

    # NIST AES-256-GCM short vector (from NIST GCM test set)
    key = bytes.fromhex('feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308')
    iv = bytes.fromhex('cafebabefacedbaddecaf888')
    pt = bytes.fromhex('d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255')
    ct_expected = bytes.fromhex('522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad')
    tag_expected = bytes.fromhex('b094dac5d93471bdec1a502270e3cc6c')
    pt_back = aes256_gcm_decrypt(key, iv, ct_expected, tag_expected)
    assert pt_back == pt, 'AES-256-GCM decrypt vector failed'

    print('ALL SELF-TESTS PASSED')


if __name__ == '__main__':
    _self_test()
