package app.verirun.security.service;

import app.verirun.entity.User;
import app.verirun.security.UserDetailsImpl;
import app.verirun.service.UserService;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserService userService;

    public UserDetailsServiceImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userService.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Email not registered"));

        if (!user.isVerified()) {
            throw new DisabledException("Email not verified");
        }

        return UserDetailsImpl.getUserDetailsFromUser(user);
    }
}