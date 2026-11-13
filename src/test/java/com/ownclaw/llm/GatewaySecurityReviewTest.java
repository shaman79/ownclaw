package com.ownclaw.llm;

import com.ownclaw.privacy.PrivateIndex;
import com.ownclaw.privacy.Redactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.ownclaw.llm.EchoProbeReviewTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** The third review: the gateway with texts a task declared skill code or a skill's output. */
class GatewaySecurityReviewTest {

    static LlmRequestConfig plain(EgressContext e) {
        return new LlmRequestConfig(null, null, false).withEgress(e);
    }

    static EgressContext declaring(Map<String, Redactor.Declared> declared) {
        return new EgressContext("u1", "t1", "think", new PrivateIndex(), Map.of(), null, null, null, declared);
    }

    @Test
    @DisplayName("F3: a short declared text splits nothing -- the part is filtered whole, an address, a known SSID and an IBAN replaced as without it")
    void aShortDeclaration() {
        var p = new Script();
        var gw = new CloudGateway(p, p, config(), x -> { }, null, new Redactor(null));
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user("my network is ssid=\"ExampleHome5G\"")), plain(declaring(Map.of())));
        String text = "Mail ops0@example.com on ExampleHome5G; pay to CZ65 0800 0000 1920 0014 5399.";
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user(text)), plain(declaring(Map.of())));
        String plainly = p.calls.getLast().getFirst().content();
        assertFalse(plainly.contains("ops0@example.com") || plainly.contains("ExampleHome5G") || plainly.contains("0800 0000"),
                "premise: " + plainly);
        for (var declared : List.of(Map.of("0", Redactor.Declared.OUTPUT), Map.of("e", Redactor.Declared.CODE),
                Map.of("0800 0000 1920 00", Redactor.Declared.CODE))) {
            p.next.add("ok");
            gw.chat(List.of(LlmMessage.user(text)), plain(declaring(declared)));
            assertEquals(plainly, p.calls.getLast().getFirst().content(), "declared " + declared);
        }
        // Mutation: filter each declared stretch apart -> the address and the IBAN, cut at every "0", go out.
    }

    @Test
    @DisplayName("F3: the detectors see the whole part -- a token around a declared text is removed")
    void aSecretAroundADeclaredText() {
        var p = new Script();
        var gw = new CloudGateway(p, p, config(), x -> { }, null, new Redactor(null));
        String token = "ghp_FakeFakeFake0123456789Fake0123456789";
        String text = "the deploy token is " + token + " -- use it";
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user(text)), plain(declaring(Map.of("Fake0123456789Fake", Redactor.Declared.OUTPUT))));
        String sent = p.calls.getLast().getFirst().content();
        assertFalse(sent.contains("Fake0123456789"), "token in clear: " + sent);
    }

    @Test
    @DisplayName("F2: a declared run's output is text: what the table does not know is replaced and learned; a known value inside a longer word is replaced too")
    void aRunsOutput() {
        var p = new Script();
        var r = new Redactor(null);
        var gw = new CloudGateway(p, p, config(), x -> { }, null, r);
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user("my network is ssid=\"ExampleHome5G\"")), plain(declaring(Map.of())));
        String output = "wrote /tmp/ExampleHome5G_backup.tar; mailed ops1@example.com at +420 601 234 567";
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user("The run printed:\n" + output)), plain(declaring(Map.of(output, Redactor.Declared.OUTPUT))));
        String sent = p.calls.getLast().getFirst().content();
        assertTrue(sent.contains("/tmp/<ssid_1>_backup.tar"), sent);
        assertFalse(sent.contains("ops1@example.com") || sent.contains("601 234 567"), sent);
        assertTrue(r.count("u1", "write to ops1@example.com", Map.of()).identifiersReplaced() > 0, "learned, as text");
        // Mutation: treat OUTPUT as code -> the address and the number go out, not learned.
    }

    @Test
    @DisplayName("F8: a value in a bytes literal, as the restore writes it there, is found by the code pass")
    void aBytesLiteral() {
        var r = new Redactor(null);
        r.filter("u1", "my network is ssid=\"Kavárna Wi-Fi\"", Map.of(), new Redactor.Tally());
        String code = "NET = b'Kav\\xc3\\xa1rna Wi-Fi'\nX = 'Kavárna Wi-Fi'\nY = 'Kav\\xe1rna Wi-Fi'\n";
        String out = r.filterCode("u1", code, Map.of(), new Redactor.Tally(), false);
        assertEquals("NET = b'<ssid_1>'\nX = '<ssid_1>'\nY = '<ssid_1>'\n", out);
        // Mutation: look for the value as written only -> its bytes and escaped forms go out.
    }

    @Test
    @DisplayName("echo: only a beginning of a handed-out text that ends at a line's end; a value after a line break is no echo")
    void echoPrefixOnly() {
        String handed = "A = '<ssid_1>'\nB = 1\n";
        assertTrue(EgressContext.HandedOut.echoes(handed, "A = '<ssid_1>'"));
        assertFalse(EgressContext.HandedOut.echoes(handed, "A = '<ssid_1>'\n"), "a turn ending in its line break is not one");
        assertFalse(EgressContext.HandedOut.echoes(handed, "A = '<ssid_1>'\nExampleHome5G"));
        assertFalse(EgressContext.HandedOut.echoes(handed, "A = '<ssid_1>'\nB"));
        assertFalse(EgressContext.HandedOut.echoes(handed, "A = 'ExampleHome5G'"));
        assertTrue(EgressContext.HandedOut.echoes(handed, "A = '<ssid_1>'\nB = 1"));
        assertFalse(EgressContext.HandedOut.echoes(handed, "A = '<ssid_1>'\nB = 1\nC"));
        assertTrue(EgressContext.HandedOut.echoes("x  \ny", "x"));
        assertFalse(EgressContext.HandedOut.echoes("x  y\n", "x"));
    }
}
