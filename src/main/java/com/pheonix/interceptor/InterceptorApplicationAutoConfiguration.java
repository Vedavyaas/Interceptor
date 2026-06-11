package com.pheonix.interceptor;

import com.pheonix.interceptor.config.InterceptorProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@ComponentScan("com.pheonix.interceptor")
@EnableConfigurationProperties(InterceptorProperties.class)
@Configuration
public class InterceptorApplicationAutoConfiguration {

}
