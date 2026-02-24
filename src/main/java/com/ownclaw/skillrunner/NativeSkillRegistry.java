package com.ownclaw.skillrunner;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NativeSkillRegistry {

    private final Map<String, NativeSkill> skills = new ConcurrentHashMap<>();

    public NativeSkillRegistry(List<NativeSkill> nativeSkills) {
        for (NativeSkill s : nativeSkills) {
            skills.put(s.name(), s);
        }
    }

    public Optional<NativeSkill> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(skills.get(name));
    }
}
