"""
Recon - REST API Client for Minecraft

A Python client library for communicating with the Recon plugin's REST API.

Supports two protocols:
  - v2 (default, recommended): AES-256-GCM (authenticated) + PBKDF2-HMAC-SHA256.
    Detects tampering and resists offline password brute-force, providing meaningful
    security even WITHOUT TLS.
  - v1 (legacy): AES-256-CBC + single SHA-256 key derivation (no authentication).

v2 requires either 'pycryptodome' or 'cryptography' to be installed.
v1 can fall back to a bundled OpenSSL binding if neither is present.

License: MIT (Mobile application distribution prohibited)
Copyright (c) 2026 Enabify
"""

import hashlib
import json
import os
import time
import uuid
from base64 import b64decode, b64encode
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError

try:
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import pad, unpad
    _USE_PYCRYPTODOME = True
except Exception:
    _USE_PYCRYPTODOME = False

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM as _CryptographyAESGCM
    _USE_CRYPTOGRAPHY = True
except Exception:
    _USE_CRYPTOGRAPHY = False


class Recon:
    """Client for communicating with the Recon Minecraft plugin via REST API."""

    def __init__(self, host: str, port: int = 4161, user: str = '',
                 password: str = '', timeout: int = 10, use_ssl: bool = False,
                 protocol: int = 2, iterations: int = 100000):
        """
        Create a new Recon client instance.

        Args:
            host:       Server hostname or IP address
            port:       Server port (default: 4161)
            user:       Authentication username
            password:   Authentication password
            timeout:    Request timeout in seconds (default: 10)
            use_ssl:    Whether to use HTTPS (default: False)
            protocol:   Protocol version: 2 (recommended) or 1 (legacy). Default: 2
            iterations: PBKDF2 iterations for v2 (must meet the server floor). Default: 100000
        """
        self.host = host
        self.port = port
        self.user = user
        self.password = password
        self.timeout = timeout
        self.use_ssl = use_ssl
        self.protocol = protocol
        self.iterations = iterations

    def send_command(self, command: str, queue: bool = True) -> dict:
        """
        Send a command to the Minecraft server.

        Returns:
            dict with 'success' (bool), 'response' (str or None), 'plainResponse' (str or None), 'error' (str or None)
        """
        nonce = uuid.uuid4().hex
        timestamp = int(time.time())
        use_v2 = self.protocol == 2
        aad = self._build_aad(self.user, nonce, timestamp)

        # Derive key and encrypt command
        if use_v2:
            key = self._derive_key_v2(self.password, nonce, timestamp, self.iterations)
            encrypted = self._encrypt_gcm(f'RCON_{command}', key, aad)
        else:
            key = self._derive_key(self.password, nonce, timestamp)
            encrypted = self._encrypt(f'RCON_{command}', key)

        # Build request payload
        request = {
            'user': self.user,
            'protocol': self.protocol,
            'nonce': nonce,
            'timestamp': timestamp,
            'queue': queue,
            'command': encrypted,
        }
        if use_v2:
            request['iterations'] = self.iterations
        payload = json.dumps(request).encode('utf-8')

        # Send HTTP POST
        scheme = 'https' if self.use_ssl else 'http'
        url = f'{scheme}://{self.host}:{self.port}/'

        try:
            req = Request(url, data=payload, method='POST')
            req.add_header('Content-Type', 'application/json')

            with urlopen(req, timeout=self.timeout) as resp:
                response_body = resp.read().decode('utf-8')

        except HTTPError as e:
            try:
                error_json = json.loads(e.read().decode('utf-8'))
                return self._error(error_json.get('error', f'HTTP {e.code}'))
            except Exception:
                return self._error(f'HTTP error {e.code}')
        except URLError as e:
            return self._error(f'Connection failed: {e.reason}')

        try:
            response_json = json.loads(response_body)
        except json.JSONDecodeError:
            return self._error('Invalid JSON response')

        if response_json.get('success'):
            try:
                server_nonce = response_json.get('nonce', '')
                server_timestamp = response_json.get('timestamp', 0)
                resp_protocol = response_json.get('protocol', self.protocol)
                resp_v2 = resp_protocol == 2
                resp_iterations = response_json.get('iterations', self.iterations)
                resp_aad = self._build_aad(self.user, server_nonce, server_timestamp)

                if resp_v2:
                    response_key = self._derive_key_v2(self.password, server_nonce, server_timestamp, resp_iterations)
                    decrypted = self._decrypt_gcm(response_json['response'], response_key, resp_aad)
                    decrypted_plain = (self._decrypt_gcm(response_json['plainResponse'], response_key, resp_aad)
                                       if 'plainResponse' in response_json else decrypted)
                else:
                    response_key = self._derive_key(self.password, server_nonce, server_timestamp)
                    decrypted = self._decrypt(response_json['response'], response_key)
                    decrypted_plain = (self._decrypt(response_json['plainResponse'], response_key)
                                       if 'plainResponse' in response_json else decrypted)

                return {
                    'success': True,
                    'response': decrypted,
                    'plainResponse': decrypted_plain,
                    'error': None,
                }
            except Exception as e:
                return self._error(f'Failed to decrypt response: {e}')

        return self._error(response_json.get('error', 'Unknown error'))

    @staticmethod
    def _error(message: str) -> dict:
        return {'success': False, 'response': None, 'plainResponse': None, 'error': message}

    @staticmethod
    def _build_aad(user: str, nonce: str, timestamp) -> str:
        return f'{user}|{nonce}|{timestamp}'

    # --- v2: PBKDF2 + AES-256-GCM ---

    @staticmethod
    def _derive_key_v2(password: str, nonce: str, timestamp, iterations: int) -> bytes:
        """Derive a 256-bit key using PBKDF2-HMAC-SHA256 (salt = 'nonce_timestamp')."""
        salt = f'{nonce}_{timestamp}'.encode('utf-8')
        return hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), salt, iterations, dklen=32)

    @staticmethod
    def _encrypt_gcm(plaintext: str, key: bytes, aad: str) -> str:
        """Encrypt with AES-256-GCM. Returns Base64(IV(12) + ciphertext + tag(16))."""
        iv = os.urandom(12)
        aad_bytes = aad.encode('utf-8')
        if _USE_PYCRYPTODOME:
            cipher = AES.new(key, AES.MODE_GCM, nonce=iv)
            cipher.update(aad_bytes)
            ct, tag = cipher.encrypt_and_digest(plaintext.encode('utf-8'))
            blob = iv + ct + tag
        elif _USE_CRYPTOGRAPHY:
            ct_and_tag = _CryptographyAESGCM(key).encrypt(iv, plaintext.encode('utf-8'), aad_bytes)
            blob = iv + ct_and_tag
        else:
            raise ImportError('AES-GCM (protocol v2) requires pycryptodome or cryptography. '
                              'Install with: pip install pycryptodome')
        return b64encode(blob).decode('ascii')

    @staticmethod
    def _decrypt_gcm(ciphertext: str, key: bytes, aad: str) -> str:
        """Decrypt AES-256-GCM from Base64(IV(12) + ciphertext + tag(16))."""
        decoded = b64decode(ciphertext)
        iv = decoded[:12]
        aad_bytes = aad.encode('utf-8')
        if _USE_PYCRYPTODOME:
            ct, tag = decoded[12:-16], decoded[-16:]
            cipher = AES.new(key, AES.MODE_GCM, nonce=iv)
            cipher.update(aad_bytes)
            return cipher.decrypt_and_verify(ct, tag).decode('utf-8')
        elif _USE_CRYPTOGRAPHY:
            ct_and_tag = decoded[12:]
            return _CryptographyAESGCM(key).decrypt(iv, ct_and_tag, aad_bytes).decode('utf-8')
        else:
            raise ImportError('AES-GCM (protocol v2) requires pycryptodome or cryptography. '
                              'Install with: pip install pycryptodome')

    # --- v1 (legacy): SHA-256 + AES-256-CBC ---

    @staticmethod
    def _derive_key(password: str, nonce: str, timestamp) -> bytes:
        """Derive a 256-bit AES key using SHA-256."""
        combined = f'{password}_{nonce}_{timestamp}'
        return hashlib.sha256(combined.encode('utf-8')).digest()

    @staticmethod
    def _encrypt(plaintext: str, key: bytes) -> str:
        """Encrypt plaintext using AES-256-CBC. Returns Base64(IV + ciphertext)."""
        iv = os.urandom(16)
        if _USE_PYCRYPTODOME:
            cipher = AES.new(key, AES.MODE_CBC, iv)
            encrypted = cipher.encrypt(pad(plaintext.encode('utf-8'), AES.block_size))
        else:
            encrypted = _aes_encrypt_stdlib(plaintext.encode('utf-8'), key, iv)
        return b64encode(iv + encrypted).decode('ascii')

    @staticmethod
    def _decrypt(ciphertext: str, key: bytes) -> str:
        """Decrypt ciphertext from Base64(IV + ciphertext) using AES-256-CBC."""
        decoded = b64decode(ciphertext)
        iv = decoded[:16]
        encrypted = decoded[16:]
        if _USE_PYCRYPTODOME:
            cipher = AES.new(key, AES.MODE_CBC, iv)
            decrypted = unpad(cipher.decrypt(encrypted), AES.block_size)
        else:
            decrypted = _aes_decrypt_stdlib(encrypted, key, iv)
        return decrypted.decode('utf-8')


def _pkcs7_pad(data: bytes, block_size: int = 16) -> bytes:
    """Apply PKCS7 padding."""
    pad_len = block_size - (len(data) % block_size)
    return data + bytes([pad_len] * pad_len)


def _pkcs7_unpad(data: bytes) -> bytes:
    """Remove PKCS7 padding."""
    pad_len = data[-1]
    if pad_len < 1 or pad_len > 16:
        raise ValueError('Invalid padding')
    return data[:-pad_len]


def _aes_encrypt_stdlib(data: bytes, key: bytes, iv: bytes) -> bytes:
    """Fallback AES-CBC encryption using ctypes to OpenSSL (if pycryptodome unavailable)."""
    try:
        import ctypes
        import ctypes.util
        libssl = ctypes.cdll.LoadLibrary(ctypes.util.find_library('ssl') or ctypes.util.find_library('crypto') or 'libcrypto.so')
        padded = _pkcs7_pad(data)
        out = ctypes.create_string_buffer(len(padded) + 16)
        out_len = ctypes.c_int(0)

        ctx = libssl.EVP_CIPHER_CTX_new()
        libssl.EVP_EncryptInit_ex(ctx, libssl.EVP_aes_256_cbc(), None, key, iv)
        libssl.EVP_EncryptUpdate(ctx, out, ctypes.byref(out_len), padded, len(padded))
        total = out_len.value
        libssl.EVP_EncryptFinal_ex(ctx, ctypes.byref(out, total), ctypes.byref(out_len))
        total += out_len.value
        libssl.EVP_CIPHER_CTX_free(ctx)
        return out.raw[:total]
    except Exception:
        raise ImportError(
            'AES encryption requires pycryptodome. Install it with: pip install pycryptodome'
        )


def _aes_decrypt_stdlib(data: bytes, key: bytes, iv: bytes) -> bytes:
    """Fallback AES-CBC decryption."""
    try:
        import ctypes
        import ctypes.util
        libssl = ctypes.cdll.LoadLibrary(ctypes.util.find_library('ssl') or ctypes.util.find_library('crypto') or 'libcrypto.so')
        out = ctypes.create_string_buffer(len(data) + 16)
        out_len = ctypes.c_int(0)

        ctx = libssl.EVP_CIPHER_CTX_new()
        libssl.EVP_DecryptInit_ex(ctx, libssl.EVP_aes_256_cbc(), None, key, iv)
        libssl.EVP_DecryptUpdate(ctx, out, ctypes.byref(out_len), data, len(data))
        total = out_len.value
        libssl.EVP_DecryptFinal_ex(ctx, ctypes.byref(out, total), ctypes.byref(out_len))
        total += out_len.value
        libssl.EVP_CIPHER_CTX_free(ctx)
        return out.raw[:total]
    except Exception:
        raise ImportError(
            'AES decryption requires pycryptodome. Install it with: pip install pycryptodome'
        )
