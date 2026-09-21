package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a step is charged for.
 * <p>
 * Anthropic reports {@code input_tokens} as only the tokens that were neither read from nor
 * written to the prompt cache; reads and writes are separate and additional. Every figure in this
 * system was derived from prompt + completion alone, so with caching working as designed — which
 * is the entire point of keeping the system prompt static — the live counter, the token_usage
 * table and every budget ceiling were reading a small fraction of the real usage.
 */
class TokenAccountingTest {

    private static ThinkResult step(int prompt, int completion, int cacheWrite, int cacheRead) {
        return new ThinkResult(null, List.of(), "", prompt + completion,
                prompt, completion, cacheWrite, cacheRead, "claude-opus-5");
    }

    @Test
    @DisplayName("a cached step counts the cache, not just the uncached remainder")
    void cachedStepCountsCache() {
        // The shape of a real production step: a small uncached delta over a large cached prefix.
        ThinkResult r = step(400, 250, 0, 11_800);
        assertEquals(650, r.totalTokens(), "the reported prompt+completion, unchanged");
        assertEquals(12_450, r.billedTokens(),
                "what is actually billed — dropping the cache read understates this by ~19x");
    }

    @Test
    @DisplayName("a cache write is billed too")
    void cacheWriteCounts() {
        assertEquals(10_700, step(500, 200, 10_000, 0).billedTokens());
    }

    @Test
    @DisplayName("a provider reporting only a total is not zeroed out")
    void totalOnlyFallback() {
        // The no-breakdown constructor: components are 0 and the total is all there is.
        ThinkResult r = new ThinkResult(null, List.of(), "", 1234);
        assertEquals(1234, r.billedTokens(),
                "falling back to the component sum here would report zero usage");
    }

    @Test
    @DisplayName("with no caching the two agree, so nothing changes for Ollama or OpenAI")
    void uncachedIsUnchanged() {
        ThinkResult r = step(3000, 500, 0, 0);
        assertEquals(r.totalTokens(), r.billedTokens());
    }
}
