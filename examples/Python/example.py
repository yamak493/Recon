"""
Recon Client - Python Example

Demonstrates how to use the Recon Python client library
to send commands to a Minecraft server via REST API.

Requirements:
    pip install pycryptodome
"""

from recon import Recon

# Configuration
host     = '127.0.0.1'
port     = 4161
user     = 'admin'
password = 'your_password'
timeout  = 10

# Create client instance
recon = Recon(host, port, user, password, timeout)

# Test connection
print('Testing connection...')
response = recon.send_command('recon test', queue=True)

if response['success']:
    print('Connection successful!')
    print(f"Response: {response['response']}")
else:
    print('Connection failed!')
    print(f"Error: {response['error']}")

print()

# Execute a command
cmd = 'say Hello from Recon Python client!'
print(f'Executing command: {cmd}')
response = recon.send_command(cmd, queue=True)

if response['success']:
    print('Command executed successfully.')
    print(f"Response: {response['response']}")
else:
    print('Command failed.')
    print(f"Error: {response['error']}")
