package app.verirun.controller;

import app.verirun.dto.AuthStatusResponse;
import app.verirun.dto.UserDTO;
import app.verirun.entity.User;
import app.verirun.security.UserDetailsImpl;
import app.verirun.service.UserService;
import app.verirun.util.TokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final TokenUtil tokenUtil;

    public AuthController(UserService userService, AuthenticationManager authenticationManager, TokenUtil tokenUtil) {
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.tokenUtil = tokenUtil;
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@Valid @RequestBody UserDTO dto) {
        Optional<User> user = userService.findByEmail(dto.email());
        if (user.isPresent()) {
            log.warn("Attempted registration with existing email: {}", dto.email());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Email already registered!");
        }

        userService.register(dto);
        log.info("New user registered with email: {}", dto.email());
        return ResponseEntity.status(HttpStatus.CREATED).body("Registration successful. Please verify your email.");
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@Valid @RequestBody UserDTO dto) {
        Authentication authentication = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(dto.email(), dto.password()));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        ResponseCookie cookie = tokenUtil.getCookie(userDetails);

        log.info("User {} logged in successfully", userDetails.getUsername());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        ResponseCookie cleanCookie = tokenUtil.getCleanCookie();
        log.info("User logged out successfully");
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cleanCookie.toString()).build();
    }

    @RequestMapping(value = "/verify-account", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<String> verifyAccount(@RequestParam("token") String token) {
        boolean verified = userService.verifyUser(token);

        if (verified) {
            log.info("User account verified successfully with token: {}", token);
            return ResponseEntity.ok("Account verified successfully.");
        } else {
            log.warn("Failed account verification attempt with token: {}", token);
            return ResponseEntity.badRequest().body("Verification failed. Token may be invalid or expired.");
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> checkAuthStatus() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof UserDetailsImpl userDetails) {
            User user = userService.findByEmail(userDetails.getUsername()).orElseThrow(() -> new UsernameNotFoundException("User not found"));

            return ResponseEntity.ok(new AuthStatusResponse(true, user.getEmail(), user.getId()));
        } else {
            return ResponseEntity.ok(new AuthStatusResponse(false, null, null));
        }
    }
}
