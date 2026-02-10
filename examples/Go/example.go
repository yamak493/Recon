// Recon Client - Go Example
//
// Demonstrates how to use the Recon Go client library
// to send commands to a Minecraft server via REST API.
//
// Run with: go run example.go recon.go
package main

import (
	"fmt"
	"time"
)

func main() {
	// Configuration
	host := "127.0.0.1"
	port := 4161
	user := "admin"
	password := "your_password"
	timeout := 10 * time.Second

	// Create client instance
	client := NewRecon(host, port, user, password, timeout)

	// Test connection
	fmt.Println("Testing connection...")
	response := client.SendCommand("recon test", true)

	if response.Success {
		fmt.Println("Connection successful!")
		fmt.Printf("Response: %s\n", response.Response)
	} else {
		fmt.Println("Connection failed!")
		fmt.Printf("Error: %s\n", response.Error)
	}

	fmt.Println()

	// Execute a command
	cmd := "say Hello from Recon Go client!"
	fmt.Printf("Executing command: %s\n", cmd)
	response = client.SendCommand(cmd, true)

	if response.Success {
		fmt.Println("Command executed successfully.")
		fmt.Printf("Response: %s\n", response.Response)
	} else {
		fmt.Println("Command failed.")
		fmt.Printf("Error: %s\n", response.Error)
	}
}
