package com.mycom.concurrencystrategies.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisScriptConfig {

    @Bean("reserveStockScript")
    RedisScript<Long> reserveStockScript() {

        return RedisScript.of(
                new ClassPathResource(
                        "redis/reserve-stock.lua"
                ),
                Long.class
        );
    }

    @Bean("releaseLockScript")
    RedisScript<Long> releaseLockScript() {

        return RedisScript.of(
                new ClassPathResource(
                        "redis/release-lock.lua"
                ),
                Long.class
        );
    }
}
