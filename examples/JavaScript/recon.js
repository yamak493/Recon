/**
 * Recon - REST API Client for Minecraft
 *
 * A JavaScript (Node.js) client library for communicating with the Recon plugin's REST API.
 * Handles AES-256-CBC encryption/decryption and secure command execution.
 *
 * @license MIT (Mobile application distribution prohibited)
 * @copyright 2026 Enabify
 */

const crypto = require('crypto');
const http = require('http');
const https = require('https');

class Recon {
    /**
     * Create a new Recon client instance.
     *
     * @param {string} host     - Server hostname or IP address
     * @param {number} port     - Server port (default: 4161)
     * @param {string} user     - Authentication username
     * @param {string} password - Authentication password
     * @param {number} timeout  - Request timeout in milliseconds (default: 10000)
     * @param {boolean} useSSL  - Whether to use HTTPS (default: false)
     */
    constructor(host, port = 4161, user = '', password = '', timeout = 10000, useSSL = false) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.timeout = timeout;
        this.useSSL = useSSL;
    }

    /**
     * Send a command to the Minecraft server.
     *
     * @param {string}  command - The command to execute (without leading /)
     * @param {boolean} queue   - Whether to queue the command if the player is offline
     * @returns {Promise<{success: boolean, response: string|null, plainResponse: string|null, error: string|null}>}
     */
    async sendCommand(command, queue = true) {
        try {
            const nonce = crypto.randomBytes(16).toString('hex');
            const timestamp = Math.floor(Date.now() / 1000);

            // Derive AES key and encrypt command
            const key = this._deriveKey(this.password, nonce, timestamp);
            const encrypted = this._encrypt(`RCON_${command}`, key);

            // Build request payload
            const payload = JSON.stringify({
                user: this.user,
                nonce: nonce,
                timestamp: timestamp,
                queue: queue,
                command: encrypted,
            });

            // Send HTTP POST
            const responseBody = await this._post(payload);
            const responseJson = JSON.parse(responseBody);

            if (responseJson.success) {
                const serverNonce = responseJson.nonce || '';
                const serverTimestamp = responseJson.timestamp || 0;
                const responseKey = this._deriveKey(this.password, serverNonce, serverTimestamp);
                const decrypted = this._decrypt(responseJson.response, responseKey);
                const decryptedPlain = responseJson.plainResponse 
                    ? this._decrypt(responseJson.plainResponse, responseKey)
                    : decrypted;

                return { success: true, response: decrypted, plainResponse: decryptedPlain, error: null };
            }

            return {
                success: false,
                response: null,
                plainResponse: null,
                error: responseJson.error || 'Unknown error',
            };
        } catch (err) {
            return {
                success: false,
                response: null,
                plainResponse: null,
                error: `Connection error: ${err.message}`,
            };
        }
    }

    /**
     * Derive a 256-bit AES key using SHA-256.
     * @private
     */
    _deriveKey(password, nonce, timestamp) {
        return crypto.createHash('sha256')
            .update(`${password}_${nonce}_${timestamp}`)
            .digest();
    }

    /**
     * Encrypt plaintext using AES-256-CBC.
     * @private
     */
    _encrypt(plaintext, key) {
        const iv = crypto.randomBytes(16);
        const cipher = crypto.createCipheriv('aes-256-cbc', key, iv);
        const encrypted = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
        return Buffer.concat([iv, encrypted]).toString('base64');
    }

    /**
     * Decrypt ciphertext using AES-256-CBC.
     * @private
     */
    _decrypt(ciphertext, key) {
        const decoded = Buffer.from(ciphertext, 'base64');
        const iv = decoded.slice(0, 16);
        const encrypted = decoded.slice(16);
        const decipher = crypto.createDecipheriv('aes-256-cbc', key, iv);
        return decipher.update(encrypted, null, 'utf8') + decipher.final('utf8');
    }

    /**
     * Send an HTTP POST request.
     * @private
     */
    _post(payload) {
        return new Promise((resolve, reject) => {
            const protocol = this.useSSL ? https : http;

            const options = {
                hostname: this.host,
                port: this.port,
                path: '/',
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Content-Length': Buffer.byteLength(payload),
                },
                timeout: this.timeout,
            };

            const req = protocol.request(options, (res) => {
                let data = '';
                res.on('data', (chunk) => { data += chunk; });
                res.on('end', () => { resolve(data); });
            });

            req.on('error', reject);
            req.on('timeout', () => {
                req.destroy();
                reject(new Error('Request timed out'));
            });

            req.write(payload);
            req.end();
        });
    }
}

module.exports = Recon;
