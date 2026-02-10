<?php
/**
 * Recon Client - PHP Example
 *
 * Demonstrates how to use the Recon PHP client library
 * to send commands to a Minecraft server via REST API.
 */

require_once __DIR__ . '/Recon.php';

use Enabify\Recon;

// Configuration
$host     = '127.0.0.1';
$port     = 4161;
$user     = 'admin';
$password = 'your_password';
$timeout  = 10;

// Create client instance
$recon = new Recon($host, $port, $user, $password, $timeout);

// Test connection
echo "Testing connection...\n";
$response = $recon->sendCommand('recon test', true);

if ($response['success']) {
    echo "Connection successful!\n";
    echo "Response: " . $response['response'] . "\n";
} else {
    echo "Connection failed!\n";
    echo "Error: " . $response['error'] . "\n";
}

echo "\n";

// Execute a command
$cmd = 'say Hello from Recon PHP client!';
echo "Executing command: $cmd\n";
$response = $recon->sendCommand($cmd, true);

if ($response['success']) {
    echo "Command executed successfully.\n";
    echo "Response: " . $response['response'] . "\n";
} else {
    echo "Command failed.\n";
    echo "Error: " . $response['error'] . "\n";
}
