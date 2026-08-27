package app.verirun.repository;

import app.verirun.entity.User;
import app.verirun.security.VerificationToken;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgreSqlTestcontainersConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class VerificationTokenRepositoryIntegrationTest {

    @Autowired
    private VerificationTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findById_shouldReloadGeneratedTokenAndUserRelationship() {
        User user = persistUser("relationship-token-owner@verirun.com");
        VerificationToken token = new VerificationToken(user);
        tokenRepository.saveAndFlush(token);

        entityManager.clear();

        VerificationToken reloaded = tokenRepository.findById(token.getId()).orElseThrow();

        assertThat(reloaded.getUser().getId()).isEqualTo(user.getId());
    }

    @Test
    void save_shouldRejectSecondTokenForSameUser() {
        User user = persistUser("single-token-owner@verirun.com");
        VerificationToken first = new VerificationToken(user);
        tokenRepository.saveAndFlush(first);
        VerificationToken second = new VerificationToken(user);

        assertThatThrownBy(() -> tokenRepository.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_shouldRejectDuplicateNonNullConfirmationToken() {
        User firstUser = persistUser("duplicate-confirmation-first@verirun.com");
        User secondUser = persistUser("duplicate-confirmation-second@verirun.com");
        VerificationToken first = new VerificationToken(firstUser);
        first.setToken("duplicate-confirmation-token");
        tokenRepository.saveAndFlush(first);
        VerificationToken second = new VerificationToken(secondUser);
        second.setToken("duplicate-confirmation-token");

        assertThatThrownBy(() -> tokenRepository.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_shouldAllowDistinctGeneratedTokensForDifferentUsers() {
        User firstUser = persistUser("distinct-token-first@verirun.com");
        User secondUser = persistUser("distinct-token-second@verirun.com");

        tokenRepository.saveAndFlush(new VerificationToken(firstUser));
        tokenRepository.saveAndFlush(new VerificationToken(secondUser));

        assertThat(tokenRepository.count()).isEqualTo(2);
    }

    private User persistUser(String email) {
        User user = new User(email);
        user.setPasswordHash("password");
        return userRepository.saveAndFlush(user);
    }
}
