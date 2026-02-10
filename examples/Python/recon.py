"""
Recon - REST API Client for Minecraft

A Python client library for communicating with the Recon plugin's REST API.
Handles AES-256-CBC encryption/decryption and secure command execution.

License: MIT (Mobile application distribution prohibited)
Copyright (c) 2026 Enabify
"""

import hashlib
import json
import os
import time
import uuid
from base64 import b64decode, b64encode
from typing import Optional
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError

try:
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import pad, unpad
    _USE_PYCRYPTODOME = True
except ImportError:
    _USE_PYCRYPTODOME = False


class Recon:
    """Client for communicating with the Recon Minecraft plugin via REST API."""

    def __init__(self, host: str, port: int = 4161, user: str = '',
                 password: str = '', timeout: int = 10, use_ssl: bool = False):
        """
        Create a new Recon client instance.

        Args:
            host:     Server hostname or IP address
            port:     Server port (default: 4161)
            user:     Authentication username
            password: Authentication password
            timeout:  Request timeout in seconds (default: 10)
            use_ssl:  Whether to use HTTPS (default: False)
        """
        self.host = host
        self.port = port
        self.user = user
        self.password = password
        self.timeout = timeout
        self.use_ssl = use_ssl

    def send_command(self, command: str, queue: bool = True) -> dict:
        """
        Send a command to the Minecraft server.

        Args:
            command: The command to execute (without leading /)
            queue:   Whether to queue the command if the player is offline

        Returns:
            dict with 'success' (bool), 'response' (str or None), 'error' (str or None)
        """
        nonce = uuid.uuid4().hex
        timestamp = int(time.time())

        # Derive AES key and encrypt command
        key = self._derive_key(self.password, nonce, timestamp)
        encrypted = self._encrypt(f'RCON_{command}', key)

        # Build request payload
        payload = json.dumps({
            'user': self.user,
            'nonce': nonce,
            'timestamp': timestamp,
            'queue': queue,
            'command': encrypted,
        }).encode('utf-8')

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
                error_body = e.read().decode('utf-8')
                error_json = json.loads(error_body)
                return {
                    'success': False,
                    'response': None,
                    'error': error_json.get('error', f'HTTP {e.code}'),
                }
            except Exception:
                return {
                    'success': False,
                    'response': None,
                    'error': f'HTTP error {e.code}',
                }
        except URLError as e:
            return {
                'success': False,
                'response': None,
                'error': f'Connection failed: {e.reason}',
            }

        try:
            response_json = json.loads(response_body)
        except json.JSONDecodeError:
            return {
                'success': False,
                'response': None,
                'error': 'Invalid JSON response',
            }

        if response_json.get('success'):
            try:
                server_nonce = response_json.get('nonce', '')
                server_timestamp = response_json.get('timestamp', 0)
                response_key = self._derive_key(self.password, server_nonce, server_timestamp)
                decrypted = self._decrypt(response_json['response'], response_key)
                return {
                    'success': True,
                    'response': decrypted,
                    'error': None,
                }
            except Exception as e:
                return {
                    'success': False,
                    'response': None,
                    'error': f'Failed to decrypt response: {e}',
                }

        return {
            'success': False,
            'response': None,
            'error': response_json.get('error', 'Unknown error'),
        }

    @staticmethod
    def _derive_key(password: str, nonce: str, timestamp: int) -> bytes:
        """Derive a 256-bit AES key using SHA-256."""
        combined = f'{password}_{nonce}_{timestamp}'
        return hashlib.sha256(combined.encode('utf-8')).digest()

    @staticmethod
    def _encrypt(plaintext: str, key: bytes) -> str:
        """Encrypt plaintext using AES-256-CBC. Returns Base64(IV + ciphertext)."""
        iv = os.urandom(16)
        if _USE_PYCRYPTODOME:
            cipher = AES.new(key, AES.MODE_CBC, iv)
            padded = pad(plaintext.encode('utf-8'), AES.block_size)
            encrypted = cipher.encrypt(padded)
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
        # Try OpenSSL EVP interface
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
