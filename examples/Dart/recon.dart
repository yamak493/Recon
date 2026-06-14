/// Recon - REST API Client for Minecraft
///
/// A Dart client library for communicating with the Recon plugin's REST API.
///
/// Supports two protocols:
///   - v2 (default, recommended): AES-256-GCM (authenticated) + PBKDF2-HMAC-SHA256.
///     Detects tampering and resists offline password brute-force, providing
///     meaningful security even WITHOUT TLS.
///   - v1 (legacy): AES-256-CBC + single SHA-256 key derivation (no authentication).
///
/// PBKDF2 is implemented with the `crypto` package's HMAC (no extra dependency).
/// AES-GCM uses `pointycastle` (a transitive dependency of `encrypt`).
///
/// License: MIT (Mobile application distribution prohibited)
/// Copyright (c) 2026 Enabify

import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:encrypt/encrypt.dart' as encrypt_pkg;
import 'package:pointycastle/export.dart' as pc;
import 'package:http/http.dart' as http;

/// Response from a Recon command execution.
class ReconResponse {
  final bool success;
  final String? response;
  final String? plainResponse;
  final String? error;

  ReconResponse({required this.success, this.response, this.plainResponse, this.error});

  @override
  String toString() {
    if (success) return 'Success: $response';
    return 'Error: $error';
  }
}

/// Client for communicating with the Recon Minecraft plugin via REST API.
class Recon {
  final String host;
  final int port;
  final String user;
  final String password;
  final Duration timeout;
  final bool useSSL;

  /// Protocol version: 2 (recommended) or 1 (legacy).
  final int protocol;

  /// PBKDF2 iterations for v2 (must meet the server floor).
  final int iterations;

  /// Create a new Recon client instance.
  ///
  /// [host] - Server hostname or IP address
  /// [port] - Server port (default: 4161)
  /// [user] - Authentication username
  /// [password] - Authentication password
  /// [timeout] - Request timeout (default: 10 seconds)
  /// [useSSL] - Whether to use HTTPS (default: false)
  /// [protocol] - Protocol version (default: 2)
  /// [iterations] - PBKDF2 iterations for v2 (default: 100000)
  Recon({
    required this.host,
    this.port = 4161,
    required this.user,
    required this.password,
    this.timeout = const Duration(seconds: 10),
    this.useSSL = false,
    this.protocol = 2,
    this.iterations = 100000,
  });

  /// Send a command to the Minecraft server.
  ///
  /// [command] - The command to execute (without leading /)
  /// [queue] - Whether to queue the command if the player is offline
  Future<ReconResponse> sendCommand(String command, [bool queue = true]) async {
    try {
      final nonce = _generateNonce();
      final timestamp = DateTime.now().millisecondsSinceEpoch ~/ 1000;
      final useV2 = protocol == 2;
      final aad = _buildAad(user, nonce, timestamp);

      // Derive key and encrypt command
      final String encrypted;
      if (useV2) {
        final key = _deriveKeyV2(password, nonce, timestamp, iterations);
        encrypted = _encryptGcm('RCON_$command', key, aad);
      } else {
        final key = _deriveKey(password, nonce, timestamp);
        encrypted = _encrypt('RCON_$command', key);
      }

      // Build request payload
      final request = <String, dynamic>{
        'user': user,
        'protocol': protocol,
        'nonce': nonce,
        'timestamp': timestamp,
        'queue': queue,
        'command': encrypted,
      };
      if (useV2) {
        request['iterations'] = iterations;
      }
      final payload = jsonEncode(request);

      // Send HTTP POST
      final scheme = useSSL ? 'https' : 'http';
      final url = Uri.parse('$scheme://$host:$port/');

      final httpResponse = await http
          .post(url, body: payload, headers: {'Content-Type': 'application/json'})
          .timeout(timeout);

      final responseJson = jsonDecode(httpResponse.body) as Map<String, dynamic>;

      if (responseJson['success'] == true) {
        final serverNonce = responseJson['nonce'] as String? ?? '';
        final serverTimestamp = (responseJson['timestamp'] as num?)?.toInt() ?? 0;
        final respProtocol = (responseJson['protocol'] as num?)?.toInt() ?? protocol;
        final respV2 = respProtocol == 2;
        final respIterations = (responseJson['iterations'] as num?)?.toInt() ?? iterations;
        final respAad = _buildAad(user, serverNonce, serverTimestamp);

        String dec(String ct) {
          if (respV2) {
            final key = _deriveKeyV2(password, serverNonce, serverTimestamp, respIterations);
            return _decryptGcm(ct, key, respAad);
          }
          final key = _deriveKey(password, serverNonce, serverTimestamp);
          return _decrypt(ct, key);
        }

        final decrypted = dec(responseJson['response'] as String);
        final decryptedPlain = responseJson.containsKey('plainResponse')
            ? dec(responseJson['plainResponse'] as String)
            : decrypted;

        return ReconResponse(success: true, response: decrypted, plainResponse: decryptedPlain);
      }

      return ReconResponse(
        success: false,
        error: responseJson['error'] as String? ?? 'Unknown error',
      );
    } catch (e) {
      return ReconResponse(success: false, error: 'Connection error: $e');
    }
  }

  String _buildAad(String user, String nonce, int timestamp) => '$user|$nonce|$timestamp';

  // --- v2: PBKDF2 + AES-256-GCM ---

  /// Derive a 256-bit key using PBKDF2-HMAC-SHA256 (salt = 'nonce_timestamp').
  Uint8List _deriveKeyV2(String password, String nonce, int timestamp, int iterations) {
    final pw = Uint8List.fromList(utf8.encode(password));
    final salt = Uint8List.fromList(utf8.encode('${nonce}_$timestamp'));
    return _pbkdf2HmacSha256(pw, salt, iterations, 32);
  }

  /// Dependency-free PBKDF2-HMAC-SHA256 (RFC 2898) using the `crypto` package's HMAC.
  Uint8List _pbkdf2HmacSha256(Uint8List password, Uint8List salt, int iterations, int keyLen) {
    const hashLen = 32;
    final blocks = (keyLen + hashLen - 1) ~/ hashLen;
    final hmac = Hmac(sha256, password);
    final out = <int>[];
    for (var b = 1; b <= blocks; b++) {
      final blockSalt = <int>[
        ...salt,
        (b >> 24) & 0xff,
        (b >> 16) & 0xff,
        (b >> 8) & 0xff,
        b & 0xff,
      ];
      var u = hmac.convert(blockSalt).bytes;
      final t = List<int>.from(u);
      for (var i = 1; i < iterations; i++) {
        u = hmac.convert(u).bytes;
        for (var j = 0; j < t.length; j++) {
          t[j] ^= u[j];
        }
      }
      out.addAll(t);
    }
    return Uint8List.fromList(out.sublist(0, keyLen));
  }

  /// Encrypt with AES-256-GCM. Returns Base64(IV(12) + ciphertext + tag(16)).
  String _encryptGcm(String plaintext, Uint8List key, String aad) {
    final iv = _secureRandomBytes(12);
    final ctWithTag = _gcm(
      forEncryption: true,
      key: key,
      iv: iv,
      aad: Uint8List.fromList(utf8.encode(aad)),
      input: Uint8List.fromList(utf8.encode(plaintext)),
    );
    final combined = Uint8List(iv.length + ctWithTag.length)
      ..setRange(0, iv.length, iv)
      ..setRange(iv.length, iv.length + ctWithTag.length, ctWithTag);
    return base64Encode(combined);
  }

  /// Decrypt AES-256-GCM from Base64(IV(12) + ciphertext + tag(16)).
  String _decryptGcm(String ciphertext, Uint8List key, String aad) {
    final decoded = base64Decode(ciphertext);
    final iv = Uint8List.fromList(decoded.sublist(0, 12));
    final ctWithTag = Uint8List.fromList(decoded.sublist(12));
    final plain = _gcm(
      forEncryption: false,
      key: key,
      iv: iv,
      aad: Uint8List.fromList(utf8.encode(aad)),
      input: ctWithTag,
    );
    return utf8.decode(plain);
  }

  /// Run AES-256-GCM via pointycastle using the explicit AEAD API
  /// (processBytes + doFinal). On decryption a tag mismatch throws.
  Uint8List _gcm({
    required bool forEncryption,
    required Uint8List key,
    required Uint8List iv,
    required Uint8List aad,
    required Uint8List input,
  }) {
    final cipher = pc.GCMBlockCipher(pc.AESEngine())
      ..init(
        forEncryption,
        pc.AEADParameters(pc.KeyParameter(key), 128, iv, aad),
      );
    final output = Uint8List(cipher.getOutputSize(input.length));
    var len = cipher.processBytes(input, 0, input.length, output, 0);
    len += cipher.doFinal(output, len);
    return Uint8List.fromList(output.sublist(0, len));
  }

  // --- v1 (legacy): SHA-256 + AES-256-CBC ---

  /// Derive a 256-bit AES key using SHA-256.
  Uint8List _deriveKey(String password, String nonce, int timestamp) {
    final combined = '${password}_${nonce}_$timestamp';
    final hash = sha256.convert(utf8.encode(combined));
    return Uint8List.fromList(hash.bytes);
  }

  /// Encrypt plaintext using AES-256-CBC. Returns Base64(IV + ciphertext).
  String _encrypt(String plaintext, Uint8List key) {
    final iv = encrypt_pkg.IV.fromSecureRandom(16);
    final encrypter = encrypt_pkg.Encrypter(
      encrypt_pkg.AES(encrypt_pkg.Key(key), mode: encrypt_pkg.AESMode.cbc),
    );
    final encrypted = encrypter.encryptBytes(utf8.encode(plaintext), iv: iv);

    final combined = Uint8List(16 + encrypted.bytes.length);
    combined.setRange(0, 16, iv.bytes);
    combined.setRange(16, combined.length, encrypted.bytes);

    return base64Encode(combined);
  }

  /// Decrypt ciphertext from Base64(IV + ciphertext) using AES-256-CBC.
  String _decrypt(String ciphertext, Uint8List key) {
    final decoded = base64Decode(ciphertext);
    final iv = encrypt_pkg.IV(Uint8List.fromList(decoded.sublist(0, 16)));
    final encrypted = encrypt_pkg.Encrypted(Uint8List.fromList(decoded.sublist(16)));

    final encrypter = encrypt_pkg.Encrypter(
      encrypt_pkg.AES(encrypt_pkg.Key(key), mode: encrypt_pkg.AESMode.cbc),
    );

    return encrypter.decrypt(encrypted, iv: iv);
  }

  Uint8List _secureRandomBytes(int length) {
    final random = Random.secure();
    return Uint8List.fromList(List<int>.generate(length, (_) => random.nextInt(256)));
  }

  /// Generate a random nonce string.
  String _generateNonce() {
    final random = Random.secure();
    final bytes = List<int>.generate(16, (_) => random.nextInt(256));
    return bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  }
}
