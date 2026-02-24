package com.ownclaw.skillrunner;

import com.ownclaw.core.StepResult;
import com.ownclaw.core.TaskStep;

import java.util.Map;

/**
 * A "native" skill implemented in Java (not a Python script).
 * Used for bridge-style integrations where running a Python file is not appropriate.
 */
public interface NativeSkill {

    /** Skill name as referenced by plans (must match manifest entry). */
    String name();

    /** Execute the skill and return a StepResult. */
    StepResult execute(TaskStep step, String userId, String taskId, Map<String, Object> resolvedParams);
}
