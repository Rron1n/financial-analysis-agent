package com.rronin.financialagent.controller;
import com.rronin.financialagent.config.ModelSettings;
import com.rronin.financialagent.config.QwenSettings;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
public class ModelsController {
    private final ModelSettings models; private final QwenSettings qwen;
    public ModelsController(ModelSettings models,QwenSettings qwen){this.models=models;this.qwen=qwen;}
    @GetMapping("/api/models") public Map<String,Object> list(){return Map.of("defaultModel",models.primary(),"models",List.of(
            Map.of("id","gpt-5.6-sol","label","GPT-5.6 Sol","available",models.apiKey()!=null&&!models.apiKey().isBlank()),
            Map.of("id","qwen3.8-max","label","Qwen3.8-Max","available",qwen.apiKey()!=null&&!qwen.apiKey().isBlank())));}
}
