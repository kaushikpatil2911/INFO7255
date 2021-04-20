package com.info7255.demo.util;


import com.info7255.demo.model.JwtKeys;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtUtil {

    private final JwtKeys jwtKeys;

    public JwtUtil(JwtKeys jwtKeys) {
        this.jwtKeys = jwtKeys;
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }
    private Claims extractAllClaims(String token) {
        return Jwts.parser().setSigningKey(jwtKeys.getPublicKey()).parseClaimsJws(token).getBody();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public String generateToken() {
        Map<String, Object> claims = new HashMap<>();
        return createToken(claims);
    }

    private String createToken(Map<String, Object> claims) {

        return Jwts.builder().setClaims(claims).setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + 1000 * 60 * 10))
                .signWith(SignatureAlgorithm.RS256, jwtKeys.getPrivateKey()).compact();
    }

    public Boolean validateToken(String token) {
        return !isTokenExpired(token);
    }
}