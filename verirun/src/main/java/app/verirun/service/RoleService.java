package app.verirun.service;

import app.verirun.entity.Role;
import app.verirun.repository.RoleRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RoleService {
    private final RoleRepository roleRepository;

    public RoleService(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @PostConstruct
    public void initRoles() {
        if (!roleRepository.existsByName("REGISTERED_USER")) {
            Role userRole = new Role();
            userRole.setName("REGISTERED_USER");
            roleRepository.save(userRole);
        }
        if (!roleRepository.existsByName("ADMIN")) {
            Role adminRole = new Role();
            adminRole.setName("ADMIN");
            roleRepository.save(adminRole);
        }
    }

    public Role getUserRole() {
        return roleRepository.findByName("REGISTERED_USER")
                .orElseThrow(() -> new IllegalStateException("REGISTERED_USER role not found"));
    }
}
