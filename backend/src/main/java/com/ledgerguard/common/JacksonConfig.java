package com.ledgerguard.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.math.BigDecimal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    /**
     * 금액(BigDecimal)은 JSON에서 항상 문자열("1250000.00")로 내보낸다.
     * JS의 부동소수점 오차를 피하기 위함이다. 입력은 숫자/문자열 모두 받는다.
     * 감사 로그 스냅샷에도 같은 규칙이 적용된다.
     */
    @Bean
    public Module bigDecimalAsStringModule() {
        SimpleModule module = new SimpleModule("bigdecimal-as-string");
        module.addSerializer(BigDecimal.class, new PlainStringSerializer());
        return module;
    }

    static class PlainStringSerializer extends JsonSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(value.toPlainString());
        }
    }
}
