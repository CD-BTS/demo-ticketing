package com.bts.demoticketing.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class BookingConfiguration {
    @Bean public Clock bookingClock() { return Clock.systemUTC(); }
}
