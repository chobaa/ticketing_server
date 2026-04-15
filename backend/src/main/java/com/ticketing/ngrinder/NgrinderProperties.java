package com.ticketing.ngrinder;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketing.ngrinder")
public record NgrinderProperties(String baseUrl, String username, String password) {}

