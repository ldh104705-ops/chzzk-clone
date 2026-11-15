package com.chzzk;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.storage.dir=build/test-uploads")
class ChzzkApplicationTests {

	@Test
	void contextLoads() {
	}

}
