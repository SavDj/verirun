package app.verirun;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VerirunApplication {

    public static void main(String[] args) {
        SpringApplication.run(VerirunApplication.class, args);
    }

}
