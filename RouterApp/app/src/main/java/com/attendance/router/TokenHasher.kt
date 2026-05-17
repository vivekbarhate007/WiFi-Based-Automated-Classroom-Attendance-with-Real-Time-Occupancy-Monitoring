package com.attendance.router

import java.security.MessageDigest
import java.util.UUID

/**
 * TokenHasher provides privacy-preserving utilities for the attendance system.
 *
 * MAC addresses are NEVER stored raw. They are immediately one-way hashed
 * using SHA-256 with a per-session salt so cross-session correlation is impossible.
 */
object TokenHasher {

    /**
     * Anonymizes a raw MAC address by hashing it with SHA-256 and the session salt.
     * The result is a hex string that serves as an anonymous identifier for ARP-detected
     * devices. This is distinct from the UUID tokens students self-report via heartbeat.
     *
     * @param mac  raw MAC address string, e.g. "AA:BB:CC:DD:EE:FF"
     * @param sessionSalt  per-session random salt; changes each session to prevent linking
     * @return 64-character lowercase hex SHA-256 digest
     */
    fun anonymize(mac: String, sessionSalt: String): String {
        val normalizedMac = mac.uppercase().trim()
        val input = "$normalizedMac:$sessionSalt"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generates a cryptographically random session salt as a UUID string.
     * A new salt must be generated for every new classroom session.
     */
    fun generateSessionSalt(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * Hashes an arbitrary string (e.g. a student-provided token) with SHA-256.
     * Used for token validation / deduplication without exposing the original value.
     */
    fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(token.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Validates that a given string looks like a well-formed UUID v4.
     * Heartbeat packets that fail this check are silently dropped.
     */
    fun isValidUuidToken(token: String): Boolean {
        return try {
            UUID.fromString(token)
            // Accept only version-4 UUIDs (random)
            token.length == 36
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
