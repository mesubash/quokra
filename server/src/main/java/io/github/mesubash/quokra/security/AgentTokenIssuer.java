package io.github.mesubash.quokra.security;

import org.springframework.security.oauth2.jwt.JwtDecoder;

// One place agent tokens may come from: an identity provider, or the dev endpoint. The decoder checks the
// signature, issuer, audience and expiry for tokens from that issuer.
public record AgentTokenIssuer(String issuer, JwtDecoder decoder) {}
