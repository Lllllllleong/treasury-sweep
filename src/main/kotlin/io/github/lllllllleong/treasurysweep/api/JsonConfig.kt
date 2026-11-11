package io.github.lllllllleong.treasurysweep.api

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.cfg.CoercionAction
import tools.jackson.databind.cfg.CoercionInputShape
import tools.jackson.databind.type.LogicalType

@Configuration(proxyBeanMethods = false)
class JsonConfig {

    /**
     * Rejects a JSON number or boolean where the API expects a string. By default Jackson would accept
     * {"amount": 10.50} and hand us "10.5", letting a float into the money path that spec section 4 keeps to strings.
     */
    @Bean
    fun strictStringCoercion() = JsonMapperBuilderCustomizer { builder ->
        builder.withCoercionConfig(LogicalType.Textual) { config ->
            config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
        }
    }
}
