package app.verirun.service;

import app.verirun.dto.UserDTO;
import app.verirun.entity.User;
import app.verirun.repository.UserRepository;
import app.verirun.repository.VerificationTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private VerificationTokenRepository tokenRepository;
    @Mock
    private RoleService roleService;
    @Mock
    private EmailService emailService;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    void register_shouldRegisterUserSuccessfully_WhenValidDTO() {
        UserDTO dto = new UserDTO("new@verirun.com", "password123");
        User mockUser = new User(dto.email());

        when(userRepository.existsByEmail(dto.email())).thenReturn(false);
        when(passwordEncoder.encode(dto.password())).thenReturn("hashed_password");
        when(userRepository.findByEmail(dto.email())).thenReturn(Optional.of(mockUser));

        String result = userService.register(dto);

        assertThat(result).contains("User registered");
        verify(userRepository, times(1)).save(any(User.class));
        verify(passwordEncoder, times(1)).encode("password123");
    }

    @Test
    void register_shouldThrowIllegalArgumentException_WhenEmailAlreadyExists() {
        UserDTO dto = new UserDTO("existing@verirun.com", "password123");
        when(userRepository.existsByEmail(dto.email())).thenReturn(true);

        assertThatThrownBy(() -> userService.register(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email already registered");

        verify(userRepository, times(1)).existsByEmail(dto.email());
        verify(userRepository, never()).save(any(User.class));
    }
}