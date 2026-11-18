package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a save from the owner's skill page writes for the skill's parameters, which the page
 * cannot edit ({@code SkillController#parametersToSave}): the file's own where they are a
 * declaration, and what the page sent where the file holds nothing skill_create would take.
 */
class SkillPageKeepsParametersTest {

    @Test
    @DisplayName("the file's own parameters are kept where they are a declaration -- a map, read in full or not, or a list that names a parameter; text, a number, nothing, and a list that names none give way to what the page sent, so a file written by hand can still be saved")
    void whatASaveWrites() {
        Object page = Map.of("url", Map.of("type", "string", "description", "", "required", false));
        Object schema = Map.of("type", "object", "properties", Map.of("url", Map.of("type", "string")),
                "patternProperties", Map.of("^header_", Map.of("type", "string")));
        assertSame(schema, SkillController.parametersToSave(schema, page));
        Object plain = Map.of("url", Map.of("type", "string"), "depth", Map.of("type", "integer"));
        assertSame(plain, SkillController.parametersToSave(plain, page));
        Object named = List.of(Map.of("name", "url", "type", "string"));
        assertSame(named, SkillController.parametersToSave(named, page));
        Object empty = Map.of();
        assertSame(empty, SkillController.parametersToSave(empty, page));
        for (Object notOne : new Object[] {null, "url, then depth", 5, "", List.of(), List.of("some text of it")}) {
            assertSame(page, SkillController.parametersToSave(notOne, page), String.valueOf(notOne));
        }
        // Mutations: the page's parameters always, as before the second review -> the schema's
        // patternProperties are gone from the file; the file's own whatever they are -> the save
        // of a file whose parameters are a line of text fails with "Invalid parameters JSON".
    }
}
