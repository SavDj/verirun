package app.verirun.controller;

import app.verirun.dto.UserDTO;
import app.verirun.entity.Role;
import app.verirun.entity.User;
import app.verirun.security.UserDetailsImpl;
import app.verirun.service.UserService;
import app.verirun.util.TokenUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvcTester mockMvcTester;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthenticationManager authenticationManager;
    @MockitoBean
    private UserDetailsService userDetailsService;
    @MockitoBean
    private TokenUtil tokenUtil;

    @BeforeEach
    void setUp() {
        mockMvcTester = MockMvcTester.create(mockMvc);
    }

    @Test
    void register_shouldReturn201CreatedWhenEmailIsNew() throws Exception {
        UserDTO dto = new UserDTO("new@verirun.com", "securePassword123");
        when(userService.findByEmail(dto.email())).thenReturn(Optional.empty());

        MvcTestResultAssert resultAssert = assertThat(mockMvcTester.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(dto)));

        resultAssert.hasStatus(HttpStatus.CREATED);
        resultAssert.bodyText().isEqualTo("Registration successful. Please verify your email.");

        verify(userService).register(dto);
    }

    @Test
    void register_shouldReturn400BadRequestWhenEmailAlreadyExists() throws Exception {
        UserDTO dto = new UserDTO("existing@verirun.com", "password123");
        User existingUser = new User("existing@verirun.com");
        when(userService.findByEmail(dto.email())).thenReturn(Optional.of(existingUser));

        MvcTestResultAssert resultAssert = assertThat(mockMvcTester.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(dto)));

        resultAssert.hasStatus(HttpStatus.BAD_REQUEST);
        resultAssert.bodyText().isEqualTo("Email already registered!");

        verify(userService, never()).register(any(UserDTO.class));
    }

    @Test
    void login_shouldSetJwtCookieWhenCredentialsAreValid() throws Exception {
        UserDTO dto = new UserDTO("user@verirun.com", "password123");

        Role role = new Role();
        role.setName("REGISTERED_USER");

        UserDetailsImpl mockUserDetails = new UserDetailsImpl(UUID.randomUUID(), "user@verirun.com", "password", role);
        Authentication mockAuth = new UsernamePasswordAuthenticationToken(mockUserDetails, null, mockUserDetails.getAuthorities());

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(mockAuth);

        ResponseCookie dummyCookie = ResponseCookie.from("jwt", "dummy-token-value").httpOnly(true).build();
        when(tokenUtil.getCookie(mockUserDetails)).thenReturn(dummyCookie);

        MvcTestResultAssert resultAssert = assertThat(mockMvcTester.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(dto)));

        resultAssert.hasStatus(HttpStatus.OK);
        resultAssert.satisfies(result -> {
            Cookie jwtCookie = result.getResponse().getCookie("jwt");
            assertThat(jwtCookie).isNotNull();
            assertThat(jwtCookie.getValue()).isEqualTo("dummy-token-value");
            assertThat(jwtCookie.isHttpOnly()).isTrue();
        });

        ArgumentCaptor<UsernamePasswordAuthenticationToken> authenticationCaptor = ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(authenticationCaptor.capture());
        assertThat(authenticationCaptor.getValue().getPrincipal()).isEqualTo(dto.email());
        assertThat(authenticationCaptor.getValue().getCredentials()).isEqualTo(dto.password());
        verify(tokenUtil).getCookie(mockUserDetails);
    }
}
