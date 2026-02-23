package com.ownclaw.skills;

import java.util.List;

/**
 * Data model for a skill entry in manifest.json.
 * Maps directly to the lightweight manifest format (not the full SKILL.yaml).
 */
public record SkillModel(
    String name,
    int version,
    String summary,
    List<String> keywords,
    String complexity,
    List<String> params,
    List<String> credentials,
    boolean reversible,
    boolean interactive
) {}
