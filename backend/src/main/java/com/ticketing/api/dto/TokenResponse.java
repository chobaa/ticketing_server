package com.ticketing.api.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresInMs) {}
