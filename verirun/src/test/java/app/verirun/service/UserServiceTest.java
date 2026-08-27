package app.verirun.service;

import app.verirun.dto.UserDTO;
import app.verirun.entity.Role;
import app.verirun.entity.User;
import app.verirun.repository.UserRepository;
import app.verirun.repository.VerificationTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    void register_shouldPersistEncodedUnverifiedUserWhenDtoIsValid() {
        UserDTO dto = new UserDTO("new@verirun.com", "password123");
        User foundUser = new User(dto.email());
        Role role = new Role();
        role.setName("REGISTERED_USER");

        when(userRepository.existsByEmail(dto.email())).thenReturn(false);
        when(passwordEncoder.encode(dto.password())).thenReturn("hashed_password");
        when(roleService.getUserRole()).thenReturn(role);
        when(userRepository.findByEmail(dto.email())).thenReturn(Optional.of(foundUser));

        userService.register(dto);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo(dto.email());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("hashed_password");
        assertThat(userCaptor.getValue().getRole()).isSameAs(role);
        assertThat(userCaptor.getValue().isVerified()).isFalse();
        verify(passwordEncoder).encode(dto.password());
    }

    @Test
    void register_shouldRejectRegistrationWhenEmailAlreadyExists() {
        UserDTO dto = new UserDTO("existing@verirun.com", "password123");
        when(userRepository.existsByEmail(dto.email())).thenReturn(true);

        assertThatThrownBy(() -> userService.register(dto)).isInstanceOf(IllegalArgumentException.class);

        verify(userRepository).existsByEmail(dto.email());
        verify(userRepository, never()).save(any(User.class));
    }
}
