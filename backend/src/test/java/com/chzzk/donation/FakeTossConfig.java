package com.chzzk.donation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the real Toss HTTP client with {@link FakeTossPaymentsClient}; no test ever calls Toss. */
@TestConfiguration(proxyBeanMethods = false)
public class FakeTossConfig {

    @Bean
    @Primary
    FakeTossPaymentsClient fakeTossPaymentsClient() {
        return new FakeTossPaymentsClient();
    }
}
