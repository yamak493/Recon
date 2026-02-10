/**
 * Recon - REST API Client for Minecraft
 *
 * A TypeScript client library for communicating with the Recon plugin's REST API.
 * Handles AES-256-CBC encryption/decryption and secure command execution.
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
    error: string | null;
}

export interface ReconOptions {
    host: string;
    port?: number;
    user: string;
    password: string;
    timeout?: number;
    useSSL?: boolean;
}

export class Recon {
    private readonly host: string;
    private readonly port: number;
    private readonly user: string;
    private readonly password: string;
    private readonly timeout: number;
    private readonly useSSL: boolean;

    /**
     * Create a new Recon client instance.
     */
    constructor(options: ReconOptions);
    constructor(host: string, port?: number, user?: string, password?: string, timeout?: number, useSSL?: boolean);
    constructor(
        hostOrOptions: string | ReconOptions,
        port: number = 4161,
        user: string = '',
        password: string = '',
        timeout: number = 10000,
        useSSL: boolean = false,
    ) {
        if (typeof hostOrOptions === 'object') {
            this.host = hostOrOptions.host;
            this.port = hostOrOptions.port ?? 4161;
            this.user = hostOrOptions.user;
            this.password = hostOrOptions.password;
            this.timeout = hostOrOptions.timeout ?? 10000;
            this.useSSL = hostOrOptions.useSSL ?? false;
        } else {
            this.host = hostOrOptions;
            this.port = port;
            this.user = user;
            this.password = password;
            this.timeout = timeout;
            this.useSSL = useSSL;
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

            const key = this.deriveKey(this.password, nonce, timestamp);
            const encrypted = this.encrypt(`RCON_${command}`, key);

            const payload = JSON.stringify({
                user: this.user,
                nonce,
                timestamp,
                queue,
                command: encrypted,
            });

            const responseBody = await this.post(payload);
            const responseJson = JSON.parse(responseBody);

            if (responseJson.success) {
                const serverNonce: string = responseJson.nonce || '';
                const serverTimestamp: number = responseJson.timestamp || 0;
                const responseKey = this.deriveKey(this.password, serverNonce, serverTimestamp);
                const decrypted = this.decrypt(responseJson.response, responseKey);

                return { success: true, response: decrypted, error: null };
            }

            return {
                success: false,
                response: null,
                error: responseJson.error || 'Unknown error',
            };
        } catch (err: any) {
            return {
                success: false,
                response: null,
                error: `Connection error: ${err.message}`,
            };
        }
    }

    private deriveKey(password: string, nonce: string, timestamp: number): Buffer {
        return crypto.createHash('sha256')
            .update(`${password}_${nonce}_${timestamp}`)
            .digest();
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
