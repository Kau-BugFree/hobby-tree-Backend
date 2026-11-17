package com.hobbytree.hobbytree.domain.auth.security;

import org.springframework.http.HttpMethod;

// method == null matches any HTTP method.
public record PublicEndpoint(HttpMethod method, String pattern) {}
