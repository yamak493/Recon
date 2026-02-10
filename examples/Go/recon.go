// Package recon provides a REST API client for the Minecraft Recon plugin.
//
// It handles AES-256-CBC encryption/decryption and secure command execution.
//
// License: MIT (Mobile application distribution prohibited)
// Copyright (c) 2026 Enabify
package recon

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// Recon is a client for communicating with the Recon Minecraft plugin via REST API.
type Recon struct {
	Host     string
	Port     int
	User     string
	Password string
	Timeout  time.Duration
	UseSSL   bool
}

// Response represents the result of a command execution.
type Response struct {
	Success  bool
	Response string
	Error    string
}

// request is the JSON body sent to the server.
type request struct {
	User      string `json:"user"`
	Nonce     string `json:"nonce"`
	Timestamp int64  `json:"timestamp"`
	Queue     bool   `json:"queue"`
	Command   string `json:"command"`
}

// serverResponse is the JSON body received from the server.
type serverResponse struct {
	User      string `json:"user"`
	Nonce     string `json:"nonce"`
	Timestamp int64  `json:"timestamp"`
	Success   bool   `json:"success"`
	Response  string `json:"response"`
	Error     string `json:"error"`
}

// NewRecon creates a new Recon client instance.
func NewRecon(host string, port int, user, password string, timeout time.Duration) *Recon {
	return &Recon{
		Host:     host,
		Port:     port,
		User:     user,
		Password: password,
		Timeout:  timeout,
		UseSSL:   false,
	}
}

// SendCommand sends a command to the Minecraft server and returns the result.
func (r *Recon) SendCommand(command string, queue bool) Response {
	nonce := generateNonce()
	timestamp := time.Now().Unix()

	// Derive AES key and encrypt command
	key := deriveKey(r.Password, nonce, timestamp)
	encrypted, err := encrypt("RCON_"+command, key)
	if err != nil {
		return Response{Success: false, Error: fmt.Sprintf("Encryption error: %v", err)}
	}

	// Build request payload
	reqBody := request{
		User:      r.User,
		Nonce:     nonce,
		Timestamp: timestamp,
		Queue:     queue,
		Command:   encrypted,
	}
	payload, err := json.Marshal(reqBody)
	if err != nil {
		return Response{Success: false, Error: fmt.Sprintf("JSON marshal error: %v", err)}
	}

	// Send HTTP POST
	scheme := "http"
	if r.UseSSL {
		scheme = "https"
	}
	url := fmt.Sprintf("%s://%s:%d/", scheme, r.Host, r.Port)

	client := &http.Client{Timeout: r.Timeout}
	resp, err := client.Post(url, "application/json", bytes.NewReader(payload))
	if err != nil {
		return Response{Success: false, Error: fmt.Sprintf("Connection error: %v", err)}
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return Response{Success: false, Error: fmt.Sprintf("Read error: %v", err)}
	}

	var srvResp serverResponse
	if err := json.Unmarshal(body, &srvResp); err != nil {
		return Response{Success: false, Error: fmt.Sprintf("JSON parse error: %v", err)}
	}

	if srvResp.Success {
		responseKey := deriveKey(r.Password, srvResp.Nonce, srvResp.Timestamp)
		decrypted, err := decrypt(srvResp.Response, responseKey)
		if err != nil {
			return Response{Success: false, Error: fmt.Sprintf("Decrypt error: %v", err)}
		}
		return Response{Success: true, Response: decrypted}
	}

	errMsg := srvResp.Error
	if errMsg == "" {
		errMsg = fmt.Sprintf("Request failed (HTTP %d)", resp.StatusCode)
	}
	return Response{Success: false, Error: errMsg}
}

// deriveKey derives a 256-bit AES key using SHA-256.
func deriveKey(password, nonce string, timestamp int64) []byte {
	combined := fmt.Sprintf("%s_%s_%d", password, nonce, timestamp)
	hash := sha256.Sum256([]byte(combined))
	return hash[:]
}

// encrypt encrypts plaintext using AES-256-CBC with a random IV.
// Returns Base64(IV + ciphertext).
func encrypt(plaintext string, key []byte) (string, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}

	data := pkcs7Pad([]byte(plaintext), aes.BlockSize)
	iv := make([]byte, aes.BlockSize)
	if _, err := io.ReadFull(rand.Reader, iv); err != nil {
		return "", err
	}

	mode := cipher.NewCBCEncrypter(block, iv)
	encrypted := make([]byte, len(data))
	mode.CryptBlocks(encrypted, data)

	combined := append(iv, encrypted...)
	return base64.StdEncoding.EncodeToString(combined), nil
}

// decrypt decrypts Base64(IV + ciphertext) using AES-256-CBC.
func decrypt(ciphertext string, key []byte) (string, error) {
	decoded, err := base64.StdEncoding.DecodeString(ciphertext)
	if err != nil {
		return "", err
	}
	if len(decoded) < aes.BlockSize {
		return "", fmt.Errorf("ciphertext too short")
	}

	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}

	iv := decoded[:aes.BlockSize]
	encrypted := decoded[aes.BlockSize:]

	if len(encrypted)%aes.BlockSize != 0 {
		return "", fmt.Errorf("ciphertext is not a multiple of block size")
	}

	mode := cipher.NewCBCDecrypter(block, iv)
	decrypted := make([]byte, len(encrypted))
	mode.CryptBlocks(decrypted, encrypted)

	unpadded, err := pkcs7Unpad(decrypted)
	if err != nil {
		return "", err
	}

	return string(unpadded), nil
}

func generateNonce() string {
	b := make([]byte, 16)
	rand.Read(b)
	return fmt.Sprintf("%x", b)
}

func pkcs7Pad(data []byte, blockSize int) []byte {
	padLen := blockSize - (len(data) % blockSize)
	padding := bytes.Repeat([]byte{byte(padLen)}, padLen)
	return append(data, padding...)
}

func pkcs7Unpad(data []byte) ([]byte, error) {
	if len(data) == 0 {
		return nil, fmt.Errorf("empty data")
	}
	padLen := int(data[len(data)-1])
	if padLen < 1 || padLen > aes.BlockSize || padLen > len(data) {
		return nil, fmt.Errorf("invalid padding")
	}
	return data[:len(data)-padLen], nil
}
