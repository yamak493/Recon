/**
 * Recon - REST API Client for Minecraft
 *
 * A JavaScript (Node.js) client library for communicating with the Recon plugin's REST API.
 *
 * Supports two protocols:
 *   - v2 (default, recommended): AES-256-GCM (authenticated) + PBKDF2-HMAC-SHA256.
 *     Detects tampering and resists offline password brute-force, providing meaningful
 *     security even WITHOUT TLS.
 *   - v1 (legacy): AES-256-CBC + single SHA-256 key derivation (no authentication).
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
     * @param {string}  host       - Server hostname or IP address
     * @param {number}  port       - Server port (default: 4161)
     * @param {string}  user       - Authentication username
     * @param {string}  password   - Authentication password
     * @param {number}  timeout    - Request timeout in milliseconds (default: 10000)
     * @param {boolean} useSSL     - Whether to use HTTPS (default: false)
     * @param {number}  protocol   - Protocol version: 2 (recommended) or 1 (legacy). Default: 2
     * @param {number}  iterations - PBKDF2 iterations for v2 (must meet the server floor). Default: 100000
     */
    constructor(host, port = 4161, user = '', password = '', timeout = 10000, useSSL = false,
                protocol = 2, iterations = 100000) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.timeout = timeout;
        this.useSSL = useSSL;
        this.protocol = protocol;
        this.iterations = iterations;
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
            const useV2 = this.protocol === 2;

            // Derive key and encrypt command (AAD binds metadata in v2)
            const aad = `${this.user}|${nonce}|${timestamp}`;
            const key = useV2
                ? this._deriveKeyV2(this.password, nonce, timestamp, this.iterations)
                : this._deriveKey(this.password, nonce, timestamp);
            const encrypted = useV2
                ? this._encryptGcm(`RCON_${command}`, key, aad)
                : this._encrypt(`RCON_${command}`, key);

            // Build request payload
            const request = {
                user: this.user,
                protocol: this.protocol,
                nonce: nonce,
                timestamp: timestamp,
                queue: queue,
                command: encrypted,
            };
            if (useV2) {
                request.iterations = this.iterations;
            }

            const responseBody = await this._post(JSON.stringify(request));
            const responseJson = JSON.parse(responseBody);

            if (responseJson.success) {
                const serverNonce = responseJson.nonce || '';
                const serverTimestamp = responseJson.timestamp || 0;
                const respProtocol = responseJson.protocol || this.protocol;
                const respV2 = respProtocol === 2;
                const respIterations = responseJson.iterations || this.iterations;
                const respAad = `${this.user}|${serverNonce}|${serverTimestamp}`;

                const responseKey = respV2
                    ? this._deriveKeyV2(this.password, serverNonce, serverTimestamp, respIterations)
                    : this._deriveKey(this.password, serverNonce, serverTimestamp);
                const dec = (ct) => (respV2
                    ? this._decryptGcm(ct, responseKey, respAad)
                    : this._decrypt(ct, responseKey));

                const decrypted = dec(responseJson.response);
                const decryptedPlain = responseJson.plainResponse ? dec(responseJson.plainResponse) : decrypted;

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

    // --- v2: PBKDF2 + AES-256-GCM ---

    /** Derive a 256-bit key using PBKDF2-HMAC-SHA256 (salt = `${nonce}_${timestamp}`). @private */
    _deriveKeyV2(password, nonce, timestamp, iterations) {
        return crypto.pbkdf2Sync(password, `${nonce}_${timestamp}`, iterations, 32, 'sha256');
    }

    /** Encrypt with AES-256-GCM. Returns Base64(IV(12) + ciphertext + tag(16)). @private */
    _encryptGcm(plaintext, key, aad) {
        const iv = crypto.randomBytes(12);
        const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
        cipher.setAAD(Buffer.from(aad, 'utf8'));
        const ct = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
        const tag = cipher.getAuthTag();
        return Buffer.concat([iv, ct, tag]).toString('base64');
    }

    /** Decrypt AES-256-GCM from Base64(IV(12) + ciphertext + tag(16)). @private */
    _decryptGcm(ciphertext, key, aad) {
        const raw = Buffer.from(ciphertext, 'base64');
        const iv = raw.subarray(0, 12);
        const tag = raw.subarray(raw.length - 16);
        const ct = raw.subarray(12, raw.length - 16);
        const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
        decipher.setAAD(Buffer.from(aad, 'utf8'));
        decipher.setAuthTag(tag);
        return Buffer.concat([decipher.update(ct), decipher.final()]).toString('utf8');
    }

    // --- v1 (legacy): SHA-256 + AES-256-CBC ---

    /** Derive a 256-bit AES key using SHA-256. @private */
    _deriveKey(password, nonce, timestamp) {
        return crypto.createHash('sha256').update(`${password}_${nonce}_${timestamp}`).digest();
    }

    /** Encrypt plaintext using AES-256-CBC. @private */
    _encrypt(plaintext, key) {
        const iv = crypto.randomBytes(16);
        const cipher = crypto.createCipheriv('aes-256-cbc', key, iv);
        const encrypted = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
        return Buffer.concat([iv, encrypted]).toString('base64');
    }

    /** Decrypt ciphertext using AES-256-CBC. @private */
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
