package ru.taska.security.oauth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OAuthBridgeProperties.class)
class OAuthBridgeConfiguration {}
