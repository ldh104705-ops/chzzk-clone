package com.chzzk.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues signed access tokens: subject = user id, plus a unique id (jti) so a token can be logged out. */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final Duration ttl;
    private final TokenDenylist denylist;

    public TokenService(JwtEncoder encoder, Duration jwtTtl, TokenDenylist denylist) {
        this.encoder = encoder;
        this.ttl = jwtTtl;
        this.denylist = denylist;
    }

    public String issue(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(user.getId()))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    /** Server-side half of logout: the token stops working immediately instead of at expiry. */
    public void revoke(Jwt token) {
        denylist.revoke(token.getId(), token.getExpiresAt());
    }
}
