package app.verirun.service;

import app.verirun.dto.UserDTO;
import app.verirun.repository.UserRepository;
import app.verirun.entity.User;
import app.verirun.repository.VerificationTokenRepository;
import app.verirun.security.VerificationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final VerificationTokenRepository verificationTokenRepository;
    private final RoleService roleService;
    private final EmailService emailService;

    @Value("${app.verification.base-url:}")
    private String baseUrl;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, VerificationTokenRepository verificationTokenRepository, RoleService roleService, EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.verificationTokenRepository = verificationTokenRepository;
        this.roleService = roleService;
        this.emailService = emailService;
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public String register(UserDTO dto) {
        if (userRepository.existsByEmail(dto.email())) {
            throw new IllegalArgumentException("Email already registered");
        }
        User user = new User(dto.email());
        user.setPasswordHash(passwordEncoder.encode(dto.password()));
        user.setRole(roleService.getUserRole());
        userRepository.save(user);
        VerificationToken token = new VerificationToken(user);
        sendVerificationEmail(dto.email(), token);
        log.info("Verification email sent to {}", user.getEmail());
        return "User registered. Please verify your email.";
    }

    private void sendVerificationEmail(String email, VerificationToken token) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("Email not registered");
        }
        User user = userOpt.get();
        if (user.isVerified()) {
            throw new IllegalStateException("Email already verified");
        }

        SimpleMailMessage mailMessage = new SimpleMailMessage();
        mailMessage.setTo(email);
        mailMessage.setSubject("Verify your VeriRun account");
        mailMessage.setText("Click the link to verify your account:\n" + baseUrl + "/verify-account?token=" + token.getToken());

        //emailService.sendEmail(mailMessage);
    }

    public boolean verifyUser(String token) {
        if (token == null) {
            return false;
        }

        VerificationToken verificationToken = verificationTokenRepository.findByToken(token);
        if (verificationToken == null) {
            log.warn("Verification failed: token {} not found", token);
            return false;
        }

        User user = userRepository.findByEmail(verificationToken.getUser().getEmail()).orElseThrow(() -> new UsernameNotFoundException("Email not registered"));
        if (user == null) {
            log.warn("Verification failed: no user found for token {}", token);
            return false;
        }

        user.setVerified(true);
        userRepository.save(user);
        log.info("User {} successfully verified", user.getEmail());
        return true;
    }
}
