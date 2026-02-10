/// Recon Client - Dart Example
///
/// Demonstrates how to use the Recon Dart client library
/// to send commands to a Minecraft server via REST API.
///
/// Dependencies (add to pubspec.yaml):
///   crypto: ^3.0.0
///   encrypt: ^5.0.0
///   http: ^1.0.0
///
/// Run with: dart run example.dart

import 'recon.dart';

Future<void> main() async {
  // Configuration
  const host = '127.0.0.1';
  const port = 4161;
  const user = 'admin';
  const password = 'your_password';

  // Create client instance
  final recon = Recon(
    host: host,
    port: port,
    user: user,
    password: password,
  );

  // Test connection
  print('Testing connection...');
  var response = await recon.sendCommand('recon test', true);

  if (response.success) {
    print('Connection successful!');
    print('Response: ${response.response}');
  } else {
    print('Connection failed!');
    print('Error: ${response.error}');
  }

  print('');

  // Execute a command
  const cmd = 'say Hello from Recon Dart client!';
  print('Executing command: $cmd');
  response = await recon.sendCommand(cmd, true);

  if (response.success) {
    print('Command executed successfully.');
    print('Response: ${response.response}');
  } else {
    print('Command failed.');
    print('Error: ${response.error}');
  }
}
