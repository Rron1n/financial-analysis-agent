package com.rronin.financialagent.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("agent.qwen")
public record QwenSettings(@DefaultValue("https://dashscope.aliyuncs.com/compatible-mode/v1") String baseUrl, String apiKey) {
    @Override public String toString(){return "QwenSettings[apiKey=REDACTED]";}
}
