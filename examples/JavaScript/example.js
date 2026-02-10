/**
 * Recon Client - JavaScript (Node.js) Example
 *
 * Demonstrates how to use the Recon JavaScript client library
 * to send commands to a Minecraft server via REST API.
 */

const Recon = require('./recon');

// Configuration
const host     = '127.0.0.1';
const port     = 4161;
const user     = 'admin';
const password = 'your_password';
const timeout  = 10000; // milliseconds

// Create client instance
const recon = new Recon(host, port, user, password, timeout);

(async () => {
    // Test connection
    console.log('Testing connection...');
    let response = await recon.sendCommand('recon test', true);

    if (response.success) {
        console.log('Connection successful!');
        console.log('Response:', response.response);
        console.log('Plain Response:', response.plainResponse);
    } else {
        console.log('Connection failed!');
        console.log('Error:', response.error);
    }

    console.log();

    // Execute a command
    const cmd = 'say Hello from Recon JavaScript client!';
    console.log(`Executing command: ${cmd}`);
    response = await recon.sendCommand(cmd, true);

    if (response.success) {
        console.log('Command executed successfully.');
        console.log('Response:', response.response);
    } else {
        console.log('Command failed.');
        console.log('Error:', response.error);
    }
})();
