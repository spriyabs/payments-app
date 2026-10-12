package com.example.payments;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(TestcontainersConfiguration.class)
class PaymentsApiApplicationTests {

    @Test
    void contextLoads() {
        // The application starts and connects to a real Postgres container.
    }
}
