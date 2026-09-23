package com.mycom.concurrencystrategies.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.RedisScript;

class RedisScriptConfigTest {

    private final RedisScriptConfig config = new RedisScriptConfig();

    @Test
    void loadsReserveStockScriptFromClasspath() {
        RedisScript<Long> script = config.reserveStockScript();

        assertThat(script.getResultType()).isEqualTo(Long.class);
        assertThat(script.getScriptAsString())
                .contains("redis.call('GET', KEYS[1])")
                .contains("redis.call('DECR', KEYS[1])");
    }

    @Test
    void loadsTokenSafeReleaseLockScriptFromClasspath() {
        RedisScript<Long> script = config.releaseLockScript();

        assertThat(script.getResultType()).isEqualTo(Long.class);
        assertThat(script.getScriptAsString())
                .contains("ARGV[1]")
                .contains("redis.call('DEL', KEYS[1])");
    }
}
