package com.ticketing.ngrinder;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NgrinderProperties.class)
public class NgrinderConfig {}

