package net.enabify.recon.client;

/**
 * Recon Client - Java Example
 *
 * Demonstrates how to use the Recon Java client library
 * to send commands to a Minecraft server via REST API.
 */
public class Example {

    public static void main(String[] args) {
        // Configuration
        String host     = "127.0.0.1";
        int    port     = 4161;
        String user     = "admin";
        String password = "your_password";
        int    timeout  = 10000; // milliseconds

        // Create client instance
        Recon recon = new Recon(host, port, user, password, timeout);

        // Test connection
        System.out.println("Testing connection...");
        Recon.ReconResponse response = recon.sendCommand("recon test", true);

        if (response.isSuccess()) {
            System.out.println("Connection successful!");
            System.out.println("Response: " + response.getResponse());
            System.out.println("Plain Response: " + response.getPlainResponse());
        } else {
            System.out.println("Connection failed!");
            System.out.println("Error: " + response.getError());
        }

        System.out.println();

        // Execute a command
        String cmd = "say Hello from Recon Java client!";
        System.out.println("Executing command: " + cmd);
        response = recon.sendCommand(cmd, true);

        if (response.isSuccess()) {
            System.out.println("Command executed successfully.");
            System.out.println("Response: " + response.getResponse());
        } else {
            System.out.println("Command failed.");
            System.out.println("Error: " + response.getError());
        }
    }
}
