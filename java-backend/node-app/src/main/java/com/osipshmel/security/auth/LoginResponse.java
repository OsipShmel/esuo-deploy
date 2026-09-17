package com.osipshmel.security.auth;

import com.osipshmel.security.Role;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.UUID;

record LoginResponse(String accessToken, String tokenType, Instant expiresAt, Role role, @Nullable UUID employeeId) {
}
