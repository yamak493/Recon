/**
 * Recon - REST API Client for Minecraft
 *
 * A TypeScript client library for communicating with the Recon plugin's REST API.
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

import * as crypto from 'crypto';
import * as http from 'http';
import * as https from 'https';

export interface ReconResponse {
    success: boolean;
    response: string | null;
    plainResponse: string | null;
    error: string | null;
}

export interface ReconOptions {
    host: string;
    port?: number;
    user: string;
    password: string;
    timeout?: number;
    useSSL?: boolean;
    /** Protocol version: 2 (recommended) or 1 (legacy). Default: 2 */
    protocol?: number;
    /** PBKDF2 iterations for v2 (must meet the server floor). Default: 100000 */
    iterations?: number;
}

export class Recon {
    private readonly host: string;
    private readonly port: number;
    private readonly user: string;
    private readonly password: string;
    private readonly timeout: number;
    private readonly useSSL: boolean;
    private readonly protocol: number;
    private readonly iterations: number;

    /**
     * Create a new Recon client instance.
     */
    constructor(options: ReconOptions);
    constructor(host: string, port?: number, user?: string, password?: string, timeout?: number, useSSL?: boolean, protocol?: number, iterations?: number);
    constructor(
        hostOrOptions: string | ReconOptions,
        port: number = 4161,
        user: string = '',
        password: string = '',
        timeout: number = 10000,
        useSSL: boolean = false,
        protocol: number = 2,
        iterations: number = 100000,
    ) {
        if (typeof hostOrOptions === 'object') {
            this.host = hostOrOptions.host;
            this.port = hostOrOptions.port ?? 4161;
            this.user = hostOrOptions.user;
            this.password = hostOrOptions.password;
            this.timeout = hostOrOptions.timeout ?? 10000;
            this.useSSL = hostOrOptions.useSSL ?? false;
            this.protocol = hostOrOptions.protocol ?? 2;
            this.iterations = hostOrOptions.iterations ?? 100000;
        } else {
            this.host = hostOrOptions;
            this.port = port;
            this.user = user;
            this.password = password;
            this.timeout = timeout;
            this.useSSL = useSSL;
            this.protocol = protocol;
            this.iterations = iterations;
        }
    }

    /**
     * Send a command to the Minecraft server.
     *
     * @param command - The command to execute (without leading /)
     * @param queue   - Whether to queue the command if the player is offline
     */
    async sendCommand(command: string, queue: boolean = true): Promise<ReconResponse> {
        try {
            const nonce = crypto.randomBytes(16).toString('hex');
            const timestamp = Math.floor(Date.now() / 1000);
            const useV2 = this.protocol === 2;

            const aad = `${this.user}|${nonce}|${timestamp}`;
            const key = useV2
                ? this.deriveKeyV2(this.password, nonce, timestamp, this.iterations)
                : this.deriveKey(this.password, nonce, timestamp);
            const encrypted = useV2
                ? this.encryptGcm(`RCON_${command}`, key, aad)
                : this.encrypt(`RCON_${command}`, key);

            const request: Record<string, unknown> = {
                user: this.user,
                protocol: this.protocol,
                nonce,
                timestamp,
                queue,
                command: encrypted,
            };
            if (useV2) {
                request.iterations = this.iterations;
            }

            const responseBody = await this.post(JSON.stringify(request));
            const responseJson = JSON.parse(responseBody);

            if (responseJson.success) {
                const serverNonce: string = responseJson.nonce || '';
                const serverTimestamp: number = responseJson.timestamp || 0;
                const respProtocol: number = responseJson.protocol || this.protocol;
                const respV2 = respProtocol === 2;
                const respIterations: number = responseJson.iterations || this.iterations;
                const respAad = `${this.user}|${serverNonce}|${serverTimestamp}`;

                const responseKey = respV2
                    ? this.deriveKeyV2(this.password, serverNonce, serverTimestamp, respIterations)
                    : this.deriveKey(this.password, serverNonce, serverTimestamp);
                const dec = (ct: string): string => (respV2
                    ? this.decryptGcm(ct, responseKey, respAad)
                    : this.decrypt(ct, responseKey));

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
        } catch (err: any) {
            return {
                success: false,
                response: null,
                plainResponse: null,
                error: `Connection error: ${err.message}`,
            };
        }
    }

    // --- v2: PBKDF2 + AES-256-GCM ---

    private deriveKeyV2(password: string, nonce: string, timestamp: number, iterations: number): Buffer {
        return crypto.pbkdf2Sync(password, `${nonce}_${timestamp}`, iterations, 32, 'sha256');
    }

    private encryptGcm(plaintext: string, key: Buffer, aad: string): string {
        const iv = crypto.randomBytes(12);
        const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
        cipher.setAAD(Buffer.from(aad, 'utf8'));
        const ct = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
        const tag = cipher.getAuthTag();
        return Buffer.concat([iv, ct, tag]).toString('base64');
    }

    private decryptGcm(ciphertext: string, key: Buffer, aad: string): string {
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

    private deriveKey(password: string, nonce: string, timestamp: number): Buffer {
        return crypto.createHash('sha256').update(`${password}_${nonce}_${timestamp}`).digest();
    }

    private encrypt(plaintext: string, key: Buffer): string {
        const iv = crypto.randomBytes(16);
        const cipher = crypto.createCipheriv('aes-256-cbc', key, iv);
        const encrypted = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
        return Buffer.concat([iv, encrypted]).toString('base64');
    }

    private decrypt(ciphertext: string, key: Buffer): string {
        const decoded = Buffer.from(ciphertext, 'base64');
        const iv = decoded.slice(0, 16);
        const encrypted = decoded.slice(16);
        const decipher = crypto.createDecipheriv('aes-256-cbc', key, iv);
        return decipher.update(encrypted, undefined, 'utf8') + decipher.final('utf8');
    }

    private post(payload: string): Promise<string> {
        return new Promise((resolve, reject) => {
            const protocol = this.useSSL ? https : http;

            const options: http.RequestOptions = {
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
                res.on('data', (chunk: string) => { data += chunk; });
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

export default Recon;
