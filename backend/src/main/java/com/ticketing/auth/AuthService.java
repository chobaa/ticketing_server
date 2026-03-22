package com.ticketing.auth;

import com.ticketing.api.dto.LoginRequest;
import com.ticketing.api.dto.RegisterRequest;
import com.ticketing.api.dto.TokenResponse;
import com.ticketing.security.JwtService;
import com.ticketing.user.User;
import com.ticketing.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Value("${ticketing.jwt.expiration-ms}")
    private long expirationMs;

    @Transactional
    public TokenResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new IllegalArgumentException("Email already registered");
        }
        User u =
                User.builder()
                        .email(req.email())
                        .passwordHash(passwordEncoder.encode(req.password()))
                        .createdAt(Instant.now())
                        .build();
        u = userRepository.save(u);
        String token = jwtService.createToken(u.getId(), u.getEmail());
        return new TokenResponse(token, "Bearer", expirationMs);
    }

    public TokenResponse login(LoginRequest req) {
        User u =
                userRepository
                        .findByEmail(req.email())
                        .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
        if (!passwordEncoder.matches(req.password(), u.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        String token = jwtService.createToken(u.getId(), u.getEmail());
        return new TokenResponse(token, "Bearer", expirationMs);
    }
}
