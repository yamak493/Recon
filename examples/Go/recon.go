// Package recon provides a REST API client for the Minecraft Recon plugin.
//
// It supports two protocols:
//   - v2 (default, recommended): AES-256-GCM (authenticated) + PBKDF2-HMAC-SHA256.
//     Detects tampering and resists offline password brute-force, providing meaningful
//     security even WITHOUT TLS.
//   - v1 (legacy): AES-256-CBC + single SHA-256 key derivation (no authentication).
//
// PBKDF2 is implemented with the standard library (crypto/hmac + crypto/sha256),
// so this client has no external dependencies and builds on any Go version.
//
// License: MIT (Mobile application distribution prohibited)
// Copyright (c) 2026 Enabify
package recon

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
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
	// Protocol version: 2 (recommended) or 1 (legacy). Defaults to 2 when zero.
	Protocol int
	// PBKDF2 iterations for v2 (must meet the server floor). Defaults to 100000 when zero.
	Iterations int
}

// Response represents the result of a command execution.
type Response struct {
	Success       bool
	Response      string
	PlainResponse string
	Error         string
}

// request is the JSON body sent to the server.
type request struct {
	User       string `json:"user"`
	Protocol   int    `json:"protocol"`
	Iterations int    `json:"iterations,omitempty"`
	Nonce      string `json:"nonce"`
	Timestamp  int64  `json:"timestamp"`
	Queue      bool   `json:"queue"`
	Command    string `json:"command"`
}

// serverResponse is the JSON body received from the server.
type serverResponse struct {
	User          string `json:"user"`
	Protocol      int    `json:"protocol"`
	Iterations    int    `json:"iterations"`
	Nonce         string `json:"nonce"`
	Timestamp     int64  `json:"timestamp"`
	Success       bool   `json:"success"`
	Response      string `json:"response"`
	PlainResponse string `json:"plainResponse"`
	Error         string `json:"error"`
}

// NewRecon creates a new Recon client instance using protocol v2 with 100000 iterations.
func NewRecon(host string, port int, user, password string, timeout time.Duration) *Recon {
	return &Recon{
		Host:       host,
		Port:       port,
		User:       user,
		Password:   password,
		Timeout:    timeout,
		UseSSL:     false,
		Protocol:   2,
		Iterations: 100000,
	}
}

// SendCommand sends a command to the Minecraft server and returns the result.
func (r *Recon) SendCommand(command string, queue bool) Response {
	protocol := r.Protocol
	if protocol == 0 {
		protocol = 2
	}
	iterations := r.Iterations
	if iterations == 0 {
		iterations = 100000
	}

	nonce := generateNonce()
	timestamp := time.Now().Unix()
	useV2 := protocol == 2

	// Derive key and encrypt command (AAD binds metadata in v2)
	aad := fmt.Sprintf("%s|%s|%d", r.User, nonce, timestamp)
	var encrypted string
	var err error
	if useV2 {
		key := deriveKeyV2(r.Password, nonce, timestamp, iterations)
		encrypted, err = encryptGCM("RCON_"+command, key, aad)
	} else {
		key := deriveKey(r.Password, nonce, timestamp)
		encrypted, err = encrypt("RCON_"+command, key)
	}
	if err != nil {
		return Response{Success: false, Error: fmt.Sprintf("Encryption error: %v", err)}
	}

	// Build request payload
	reqBody := request{
		User:      r.User,
		Protocol:  protocol,
		Nonce:     nonce,
		Timestamp: timestamp,
		Queue:     queue,
		Command:   encrypted,
	}
	if useV2 {
		reqBody.Iterations = iterations
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
		respV2 := srvResp.Protocol == 2 || (srvResp.Protocol == 0 && useV2)
		respAad := fmt.Sprintf("%s|%s|%d", r.User, srvResp.Nonce, srvResp.Timestamp)
		dec := func(ct string) (string, error) {
			if respV2 {
				respIter := srvResp.Iterations
				if respIter == 0 {
					respIter = iterations
				}
				key := deriveKeyV2(r.Password, srvResp.Nonce, srvResp.Timestamp, respIter)
				return decryptGCM(ct, key, respAad)
			}
			key := deriveKey(r.Password, srvResp.Nonce, srvResp.Timestamp)
			return decrypt(ct, key)
		}

		decrypted, err := dec(srvResp.Response)
		if err != nil {
			return Response{Success: false, Error: fmt.Sprintf("Decrypt error: %v", err)}
		}
		decryptedPlain := decrypted
		if srvResp.PlainResponse != "" {
			if plain, err := dec(srvResp.PlainResponse); err == nil {
				decryptedPlain = plain
			}
		}
		return Response{Success: true, Response: decrypted, PlainResponse: decryptedPlain}
	}

	errMsg := srvResp.Error
	if errMsg == "" {
		errMsg = fmt.Sprintf("Request failed (HTTP %d)", resp.StatusCode)
	}
	return Response{Success: false, Error: errMsg}
}

// --- v2: PBKDF2 + AES-256-GCM ---

// deriveKeyV2 derives a 256-bit key using PBKDF2-HMAC-SHA256 (salt = "nonce_timestamp").
func deriveKeyV2(password, nonce string, timestamp int64, iterations int) []byte {
	salt := []byte(fmt.Sprintf("%s_%d", nonce, timestamp))
	return pbkdf2HMACSHA256([]byte(password), salt, iterations, 32)
}

// pbkdf2HMACSHA256 is a dependency-free PBKDF2 implementation (RFC 2898).
func pbkdf2HMACSHA256(password, salt []byte, iterations, keyLen int) []byte {
	hashLen := sha256.Size
	blocks := (keyLen + hashLen - 1) / hashLen
	out := make([]byte, 0, blocks*hashLen)
	for block := 1; block <= blocks; block++ {
		mac := hmac.New(sha256.New, password)
		mac.Write(salt)
		mac.Write([]byte{byte(block >> 24), byte(block >> 16), byte(block >> 8), byte(block)})
		u := mac.Sum(nil)
		t := make([]byte, len(u))
		copy(t, u)
		for i := 1; i < iterations; i++ {
			mac = hmac.New(sha256.New, password)
			mac.Write(u)
			u = mac.Sum(nil)
			for j := range t {
				t[j] ^= u[j]
			}
		}
		out = append(out, t...)
	}
	return out[:keyLen]
}

// encryptGCM encrypts plaintext using AES-256-GCM. Returns Base64(IV(12) + ciphertext + tag(16)).
func encryptGCM(plaintext string, key []byte, aad string) (string, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	iv := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, iv); err != nil {
		return "", err
	}
	ct := gcm.Seal(nil, iv, []byte(plaintext), []byte(aad))
	return base64.StdEncoding.EncodeToString(append(iv, ct...)), nil
}

// decryptGCM decrypts Base64(IV(12) + ciphertext + tag(16)) using AES-256-GCM.
func decryptGCM(ciphertext string, key []byte, aad string) (string, error) {
	decoded, err := base64.StdEncoding.DecodeString(ciphertext)
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	ivSize := gcm.NonceSize()
	if len(decoded) < ivSize+gcm.Overhead() {
		return "", fmt.Errorf("ciphertext too short")
	}
	iv := decoded[:ivSize]
	ct := decoded[ivSize:]
	pt, err := gcm.Open(nil, iv, ct, []byte(aad))
	if err != nil {
		return "", err
	}
	return string(pt), nil
}

// --- v1 (legacy): SHA-256 + AES-256-CBC ---

// deriveKey derives a 256-bit AES key using SHA-256.
func deriveKey(password, nonce string, timestamp int64) []byte {
	combined := fmt.Sprintf("%s_%s_%d", password, nonce, timestamp)
	hash := sha256.Sum256([]byte(combined))
	return hash[:]
}

// encrypt encrypts plaintext using AES-256-CBC with a random IV. Returns Base64(IV + ciphertext).
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
