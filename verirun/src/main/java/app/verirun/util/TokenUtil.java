package app.verirun.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class TokenUtil {
    @Value("${app.jwt.secret:verirun-app}")
    private String APP_NAME;

    private final SecretKey key;

    public TokenUtil() {
        String secret = System.getenv("JWT_SECRET");
        if (secret == null || secret.isEmpty()) {
            secret = "verirun-sim-default-secret-key-jwt";
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
    }

    @Value("1800000")
    private int EXPIRES_IN;

    private static final String AUDIENCE_WEB = "web";

    public String getTokenFromCookie(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, APP_NAME);
        if (cookie == null) {
            return null;
        } else {
            return cookie.getValue();
        }
    }

    public boolean validateToken(String token, UserDetails userDetails) {
        String username = getUsername(token);
        return (username.equals(userDetails.getUsername()) && !getExpirationDate(token).before(new Date()));
    }

    public ResponseCookie getCookie(UserDetails userPrincipal) {
        String jwt = generateToken(userPrincipal.getUsername());
        return ResponseCookie.from(APP_NAME, jwt)
                .path("/")
                .maxAge(24 * 60 * 60)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .build();
    }

    public ResponseCookie getCleanCookie() {
        return ResponseCookie.from(APP_NAME, null)
                .path("/")
                .build();
    }

    public String getUsername(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public Date getExpirationDate(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getExpiration();
    }

    public String generateToken(String username) {
        return Jwts.builder()
                .setIssuer(APP_NAME)
                .setAudience(AUDIENCE_WEB)
                .setSubject(username)
                .setIssuedAt(new Date())
                .setExpiration(new Date((new Date()).getTime() + EXPIRES_IN))
                .signWith(SignatureAlgorithm.HS256, key)
                .compact();
    }
}
