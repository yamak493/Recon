<?php
/**
 * Recon - REST API Client for Minecraft
 *
 * A PHP client library for communicating with the Recon plugin's REST API.
 *
 * Supports two protocols:
 *   - v2 (default, recommended): AES-256-GCM (authenticated) + PBKDF2-HMAC-SHA256.
 *     Detects tampering and resists offline password brute-force, providing meaningful
 *     security even WITHOUT TLS.
 *   - v1 (legacy): AES-256-CBC + single SHA-256 key derivation (no authentication).
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
    private $protocol;
    private $iterations;

    /**
     * Create a new Recon client instance.
     *
     * @param string $host       Server hostname or IP address
     * @param int    $port       Server port (default: 4161)
     * @param string $user       Authentication username
     * @param string $password   Authentication password
     * @param int    $timeout    Request timeout in seconds (default: 10)
     * @param bool   $useSSL     Whether to use HTTPS (default: false)
     * @param int    $protocol   Protocol version: 2 (recommended) or 1 (legacy). Default: 2
     * @param int    $iterations PBKDF2 iterations for v2 (must meet the server floor). Default: 100000
     */
    public function __construct(string $host, int $port = 4161, string $user = '', string $password = '', int $timeout = 10, bool $useSSL = false, int $protocol = 2, int $iterations = 100000)
    {
        $this->host = $host;
        $this->port = $port;
        $this->user = $user;
        $this->password = $password;
        $this->timeout = $timeout;
        $this->useSSL = $useSSL;
        $this->protocol = $protocol;
        $this->iterations = $iterations;
    }

    /**
     * Send a command to the Minecraft server and return the result.
     *
     * @param string $command The command to execute (without leading /)
     * @param bool   $queue   Whether to queue the command if the player is offline
     * @return array Response array with 'success', 'response', 'plainResponse', and optional 'error' keys
     */
    public function sendCommand(string $command, bool $queue = true): array
    {
        $nonce = $this->generateNonce();
        $timestamp = time();
        $useV2 = $this->protocol === 2;

        // Derive key and encrypt command (AAD binds metadata in v2)
        $aad = $this->buildAad($this->user, $nonce, $timestamp);
        if ($useV2) {
            $key = $this->deriveKeyV2($this->password, $nonce, $timestamp, $this->iterations);
            $encrypted = $this->encryptGcm('RCON_' . $command, $key, $aad);
        } else {
            $key = $this->deriveKey($this->password, $nonce, $timestamp);
            $encrypted = $this->encrypt('RCON_' . $command, $key);
        }

        // Build request payload
        $request = [
            'user'      => $this->user,
            'protocol'  => $this->protocol,
            'nonce'     => $nonce,
            'timestamp' => $timestamp,
            'queue'     => $queue,
            'command'   => $encrypted,
        ];
        if ($useV2) {
            $request['iterations'] = $this->iterations;
        }
        $payload = json_encode($request);

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
                $respProtocol = $responseJson['protocol'] ?? $this->protocol;
                $respV2 = $respProtocol === 2;
                $respIterations = $responseJson['iterations'] ?? $this->iterations;
                $respAad = $this->buildAad($this->user, $serverNonce, $serverTimestamp);

                if ($respV2) {
                    $responseKey = $this->deriveKeyV2($this->password, $serverNonce, $serverTimestamp, $respIterations);
                    $decrypted = $this->decryptGcm($responseJson['response'], $responseKey, $respAad);
                    $decryptedPlain = isset($responseJson['plainResponse'])
                        ? $this->decryptGcm($responseJson['plainResponse'], $responseKey, $respAad)
                        : $decrypted;
                } else {
                    $responseKey = $this->deriveKey($this->password, $serverNonce, $serverTimestamp);
                    $decrypted = $this->decrypt($responseJson['response'], $responseKey);
                    $decryptedPlain = isset($responseJson['plainResponse'])
                        ? $this->decrypt($responseJson['plainResponse'], $responseKey)
                        : $decrypted;
                }

                return [
                    'success'       => true,
                    'response'      => $decrypted,
                    'plainResponse' => $decryptedPlain,
                    'error'         => null,
                ];
            } catch (\Exception $e) {
                return [
                    'success'       => false,
                    'response'      => null,
                    'plainResponse' => null,
                    'error'         => 'Failed to decrypt response: ' . $e->getMessage(),
                ];
            }
        }

        return [
            'success'       => $responseJson['success'] ?? false,
            'response'      => null,
            'plainResponse' => null,
            'error'         => $responseJson['error'] ?? 'Unknown error',
        ];
    }

    private function buildAad(string $user, string $nonce, int $timestamp): string
    {
        return $user . '|' . $nonce . '|' . $timestamp;
    }

    // --- v2: PBKDF2 + AES-256-GCM ---

    /**
     * Derive a 256-bit key using PBKDF2-HMAC-SHA256 (salt = "nonce_timestamp").
     */
    private function deriveKeyV2(string $password, string $nonce, int $timestamp, int $iterations): string
    {
        return hash_pbkdf2('sha256', $password, $nonce . '_' . $timestamp, $iterations, 32, true);
    }

    /**
     * Encrypt with AES-256-GCM. Output: Base64(IV(12) + ciphertext + tag(16)).
     */
    private function encryptGcm(string $plaintext, string $key, string $aad): string
    {
        $iv = random_bytes(12);
        $tag = '';
        $encrypted = openssl_encrypt($plaintext, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag, $aad, 16);
        if ($encrypted === false) {
            throw new \RuntimeException('Encryption failed');
        }
        return base64_encode($iv . $encrypted . $tag);
    }

    /**
     * Decrypt AES-256-GCM from Base64(IV(12) + ciphertext + tag(16)).
     */
    private function decryptGcm(string $ciphertext, string $key, string $aad): string
    {
        $decoded = base64_decode($ciphertext, true);
        if ($decoded === false || strlen($decoded) < 28) {
            throw new \RuntimeException('Invalid ciphertext');
        }
        $iv = substr($decoded, 0, 12);
        $tag = substr($decoded, -16);
        $encrypted = substr($decoded, 12, strlen($decoded) - 28);
        $decrypted = openssl_decrypt($encrypted, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag, $aad);
        if ($decrypted === false) {
            throw new \RuntimeException('Decryption failed (authentication tag mismatch)');
        }
        return $decrypted;
    }

    // --- v1 (legacy): SHA-256 + AES-256-CBC ---

    /**
     * Derive a 256-bit AES key using SHA-256.
     */
    private function deriveKey(string $password, string $nonce, int $timestamp): string
    {
        return hash('sha256', $password . '_' . $nonce . '_' . $timestamp, true);
    }

    /**
     * Encrypt plaintext using AES-256-CBC. Output: Base64(IV + ciphertext).
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
     * Decrypt ciphertext using AES-256-CBC. Input: Base64(IV + ciphertext).
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
