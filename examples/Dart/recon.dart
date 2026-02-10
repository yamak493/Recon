/// Recon - REST API Client for Minecraft
///
/// A Dart client library for communicating with the Recon plugin's REST API.
/// Handles AES-256-CBC encryption/decryption and secure command execution.
///
/// This library is also used by the Recon Flutter application.
///
/// License: MIT (Mobile application distribution prohibited)
/// Copyright (c) 2026 Enabify

import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:encrypt/encrypt.dart' as encrypt_pkg;
import 'package:http/http.dart' as http;

/// Response from a Recon command execution.
class ReconResponse {
  final bool success;
  final String? response;
  final String? error;

  ReconResponse({required this.success, this.response, this.error});

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

  /// Create a new Recon client instance.
  ///
  /// [host] - Server hostname or IP address
  /// [port] - Server port (default: 4161)
  /// [user] - Authentication username
  /// [password] - Authentication password
  /// [timeout] - Request timeout (default: 10 seconds)
  /// [useSSL] - Whether to use HTTPS (default: false)
  Recon({
    required this.host,
    this.port = 4161,
    required this.user,
    required this.password,
    this.timeout = const Duration(seconds: 10),
    this.useSSL = false,
  });

  /// Send a command to the Minecraft server.
  ///
  /// [command] - The command to execute (without leading /)
  /// [queue] - Whether to queue the command if the player is offline
  Future<ReconResponse> sendCommand(String command, [bool queue = true]) async {
    try {
      final nonce = _generateNonce();
      final timestamp = DateTime.now().millisecondsSinceEpoch ~/ 1000;

      // Derive AES key and encrypt command
      final key = _deriveKey(password, nonce, timestamp);
      final encrypted = _encrypt('RCON_$command', key);

      // Build request payload
      final payload = jsonEncode({
        'user': user,
        'nonce': nonce,
        'timestamp': timestamp,
        'queue': queue,
        'command': encrypted,
      });

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
        final responseKey = _deriveKey(password, serverNonce, serverTimestamp);
        final decrypted = _decrypt(responseJson['response'] as String, responseKey);

        return ReconResponse(success: true, response: decrypted);
      }

      return ReconResponse(
        success: false,
        error: responseJson['error'] as String? ?? 'Unknown error',
      );
    } catch (e) {
      return ReconResponse(success: false, error: 'Connection error: $e');
    }
  }

  /// Derive a 256-bit AES key using SHA-256.
  Uint8List _deriveKey(String password, String nonce, int timestamp) {
    final combined = '${password}_${nonce}_$timestamp';
    final hash = sha256.convert(utf8.encode(combined));
    return Uint8List.fromList(hash.bytes);
  }

  /// Encrypt plaintext using AES-256-CBC.
  /// Returns Base64(IV + ciphertext).
  String _encrypt(String plaintext, Uint8List key) {
    final iv = encrypt_pkg.IV.fromSecureRandom(16);
    final encrypter = encrypt_pkg.Encrypter(
      encrypt_pkg.AES(encrypt_pkg.Key(key), mode: encrypt_pkg.AESMode.cbc),
    );
    final encrypted = encrypter.encryptBytes(utf8.encode(plaintext), iv: iv);

    // Combine IV + ciphertext
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

  /// Generate a random nonce string.
  String _generateNonce() {
    final random = Random.secure();
    final bytes = List<int>.generate(16, (_) => random.nextInt(256));
    return bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  }
}
