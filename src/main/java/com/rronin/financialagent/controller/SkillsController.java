package com.rronin.financialagent.controller;

import com.rronin.financialagent.skills.SkillRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills")
public class SkillsController {
    private final SkillRegistry skills;

    public SkillsController(SkillRegistry skills) {
        this.skills = skills;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return skills.list();
    }

    @GetMapping(value = "/{id}/content", produces = MediaType.TEXT_PLAIN_VALUE)
    public String content(@PathVariable String id) throws IOException {
        return skills.content(id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, String> body) throws IOException {
        return skills.upsert(id, body.getOrDefault("content", ""));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) throws IOException {
        skills.delete(id);
        return Map.of("deleted", true);
    }
}
