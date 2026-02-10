<?php
/**
 * Recon - REST API Client for Minecraft
 *
 * A PHP client library for communicating with the Recon plugin's REST API.
 * Handles AES-256-CBC encryption/decryption and secure command execution.
 *
 * @package Enabify\Recon
 * @license MIT (Mobile application distribution prohibited)
 * @copyright 2026 Enabify
 */

namespace Enabify;

class Recon
{
    private $host;
    private $port;
    private $user;
    private $password;
    private $timeout;
    private $useSSL;

    /**
     * Create a new Recon client instance.
     *
     * @param string $host     Server hostname or IP address
     * @param int    $port     Server port (default: 4161)
     * @param string $user     Authentication username
     * @param string $password Authentication password
     * @param int    $timeout  Request timeout in seconds (default: 10)
     * @param bool   $useSSL   Whether to use HTTPS (default: false)
     */
    public function __construct(string $host, int $port = 4161, string $user = '', string $password = '', int $timeout = 10, bool $useSSL = false)
    {
        $this->host = $host;
        $this->port = $port;
        $this->user = $user;
        $this->password = $password;
        $this->timeout = $timeout;
        $this->useSSL = $useSSL;
    }

    /**
     * Send a command to the Minecraft server and return the result.
     *
     * @param string $command The command to execute (without leading /)
     * @param bool   $queue   Whether to queue the command if the player is offline
     * @return array Response array with 'success', 'response', and optional 'error' keys
     */
    public function sendCommand(string $command, bool $queue = true): array
    {
        $nonce = $this->generateNonce();
        $timestamp = time();

        // Derive AES key
        $key = $this->deriveKey($this->password, $nonce, $timestamp);

        // Encrypt command with RCON_ prefix
        $encrypted = $this->encrypt('RCON_' . $command, $key);

        // Build request payload
        $payload = json_encode([
            'user'      => $this->user,
            'nonce'     => $nonce,
            'timestamp' => $timestamp,
            'queue'     => $queue,
            'command'   => $encrypted,
        ]);

        // Send HTTP POST request
        $url = sprintf('%s://%s:%d/', $this->useSSL ? 'https' : 'http', $this->host, $this->port);

        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_POST           => true,
            CURLOPT_POSTFIELDS     => $payload,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_HTTPHEADER     => ['Content-Type: application/json'],
            CURLOPT_TIMEOUT        => $this->timeout,
            CURLOPT_CONNECTTIMEOUT => $this->timeout,
        ]);

        if (!$this->useSSL) {
            curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, false);
        }

        $responseBody = curl_exec($ch);
        $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $error = curl_error($ch);
        curl_close($ch);

        if ($responseBody === false) {
            return [
                'success'  => false,
                'response' => null,
                'error'    => 'Connection failed: ' . $error,
            ];
        }

        $responseJson = json_decode($responseBody, true);
        if ($responseJson === null) {
            return [
                'success'  => false,
                'response' => null,
                'error'    => 'Invalid JSON response (HTTP ' . $httpCode . ')',
            ];
        }

        // Decrypt response if present
        if (isset($responseJson['success']) && $responseJson['success'] === true && isset($responseJson['response'])) {
            try {
                $serverNonce = $responseJson['nonce'] ?? '';
                $serverTimestamp = $responseJson['timestamp'] ?? 0;
                $responseKey = $this->deriveKey($this->password, $serverNonce, $serverTimestamp);
                $decrypted = $this->decrypt($responseJson['response'], $responseKey);

                return [
                    'success'  => true,
                    'response' => $decrypted,
                    'error'    => null,
                ];
            } catch (\Exception $e) {
                return [
                    'success'  => false,
                    'response' => null,
                    'error'    => 'Failed to decrypt response: ' . $e->getMessage(),
                ];
            }
        }

        return [
            'success'  => $responseJson['success'] ?? false,
            'response' => null,
            'error'    => $responseJson['error'] ?? 'Unknown error',
        ];
    }

    /**
     * Derive a 256-bit AES key using SHA-256.
     */
    private function deriveKey(string $password, string $nonce, int $timestamp): string
    {
        return hash('sha256', $password . '_' . $nonce . '_' . $timestamp, true);
    }

    /**
     * Encrypt plaintext using AES-256-CBC.
     * Output: Base64(IV + ciphertext)
     */
    private function encrypt(string $plaintext, string $key): string
    {
        $iv = openssl_random_pseudo_bytes(16);
        $encrypted = openssl_encrypt($plaintext, 'aes-256-cbc', $key, OPENSSL_RAW_DATA, $iv);

        if ($encrypted === false) {
            throw new \RuntimeException('Encryption failed');
        }

        return base64_encode($iv . $encrypted);
    }

    /**
     * Decrypt ciphertext using AES-256-CBC.
     * Input: Base64(IV + ciphertext)
     */
    private function decrypt(string $ciphertext, string $key): string
    {
        $decoded = base64_decode($ciphertext, true);
        if ($decoded === false || strlen($decoded) < 16) {
            throw new \RuntimeException('Invalid ciphertext');
        }

        $iv = substr($decoded, 0, 16);
        $encrypted = substr($decoded, 16);

        $decrypted = openssl_decrypt($encrypted, 'aes-256-cbc', $key, OPENSSL_RAW_DATA, $iv);

        if ($decrypted === false) {
            throw new \RuntimeException('Decryption failed');
        }

        return $decrypted;
    }

    /**
     * Generate a random nonce string.
     */
    private function generateNonce(): string
    {
        return bin2hex(random_bytes(16));
    }
}
