package com.ownclaw.llm;

import com.ownclaw.privacy.PrivateIndex;
import com.ownclaw.privacy.Redactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.ownclaw.llm.EchoProbeReviewTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What the gateway sent a declared text as ({@link EgressContext.SentAs}): the stretch of the
 * filtered part the text became, said by the pass that wrote it. A skill's code changed by the
 * lines a model names ({@code SkillEdits}) is changed in that text -- the one the model read -- and
 * it is not the text the task holds.
 */
class SentAsTest {

    static EgressContext declaring(Map<String, Redactor.Declared> declared) {
        return new EgressContext("u1", "t1", "codegen", new PrivateIndex(), Map.of(), null, null, null, declared);
    }

    static LlmRequestConfig plain(EgressContext e) {
        return new LlmRequestConfig(null, null, false).withEgress(e);
    }

    static String request(String code) {
        return "Fix this skill:\n```python\n" + code + "\n```\nReturn the change.\n";
    }

    /** What stands in the fence of the last request the provider received. */
    static String received(Script p) {
        String text = p.calls.getLast().getLast().content();
        return text.substring(text.indexOf("```python\n") + 10, text.lastIndexOf("\n```\n"));
    }

    @Test
    @DisplayName("the code as the model wrote it goes as it is where the table knows its placeholders, and that is said; a placeholder of nobody's, an address of the model's own and a value in clear go as other text, and that is said: each time the very stretch the provider received")
    void theCodeAsItWentOut() {
        var p = new Script();
        var gw = new CloudGateway(p, p, config(), x -> { }, null, new Redactor(null));
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user("my networks: ssid=\"ExampleHome5G\", ssid=\"AP7\"")), plain(declaring(Map.of())));

        String written = "import json\nNET = '<ssid_1>'\nGUEST = '<ssid_2>'\n\ndef run(params):\n    return {'output': NET}";
        var e = declaring(Map.of(written, Redactor.Declared.CODE_AS_WRITTEN));
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.system("Write code."), LlmMessage.user(request(written))), plain(e));
        assertEquals(written, e.sentAs().of(written), "as it is");
        assertEquals(written, received(p));

        String other = written + "\nOTHER = '<ssid_9>'\nTO = 'reports@example.org'\nARCHIVE = '/tmp/ExampleHome5G_backup.tar'";
        for (var kind : List.of(Redactor.Declared.CODE_AS_WRITTEN, Redactor.Declared.CODE)) {
            var changed = declaring(Map.of(other, kind));
            p.next.add("ok");
            gw.chat(List.of(LlmMessage.system("Write code."), LlmMessage.user(request(other))), plain(changed));
            String sent = changed.sentAs().of(other);
            assertEquals(received(p), sent, kind + ": what the provider received, and no other text");
            assertNotEquals(other, sent, kind + ": not the text the task holds");
            assertTrue(sent.contains("OTHER = '<text_") && sent.contains("TO = '<email_1>'")
                    && sent.contains("ARCHIVE = '/tmp/<ssid_1>_backup.tar'"), kind + ": " + sent);
        }
        assertNull(e.sentAs().of("import json\nNET = 'another text'"), "a text the context did not declare");
        // Mutation: say the declared text itself -> "OTHER = '<ssid_9>'" where the provider received '<text_1>'.
    }

    @Test
    @DisplayName("only the pass over the whole part knows it: a network the specification names is replaced in the code under it, and the filter of the code alone leaves it")
    void theSameRequestNamesIt() {
        var p = new Script();
        var gw = new CloudGateway(p, p, config(), x -> { }, null, new Redactor(null));
        String code = "import json\nDEFAULT = 'OpenWrt-Guest'\n\ndef run(params):\n    return {'output': DEFAULT}";
        var e = declaring(Map.of(code, Redactor.Declared.CODE_AS_WRITTEN));
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user("The guests use ssid=\"OpenWrt-Guest\".\n" + request(code))), plain(e));

        assertEquals(code.replace("OpenWrt-Guest", "<ssid_1>"), e.sentAs().of(code));
        assertEquals(e.sentAs().of(code), received(p));
        assertEquals(code, new Redactor(null).filterCode("u1", code, Map.of(), new Redactor.Tally(), true),
                "the premise: by itself, the code holds nothing the filter knows");
        // Mutation: work it out from the code alone, before the part is filtered -> a text the model was not shown.
    }

    @Test
    @DisplayName("nothing is said where it cannot be: a finding lies across the end of the declared text, the text is shorter than the gateway looks for, or the request does not hold it")
    void whereItCannotBeSaid() {
        var p = new Script();
        var gw = new CloudGateway(p, p, config(), x -> { }, null, new Redactor(null));
        String cut = "import json\nCONTACT = 'ops0@example";
        String tiny = "def run(p):pass";
        String absent = "import os\n\ndef run(params):\n    return {}";
        var e = declaring(Map.of(cut, Redactor.Declared.CODE, tiny, Redactor.Declared.CODE, absent, Redactor.Declared.CODE));
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user(cut + ".com'\n\n" + tiny + "\n")), plain(e));

        String sent = p.calls.getLast().getFirst().content();
        assertTrue(sent.startsWith("import json\nCONTACT = '<email_1>'"), "the premise: the address was replaced whole: " + sent);
        assertNull(e.sentAs().of(cut), "where it ends, the address's placeholder has only begun");
        assertNull(e.sentAs().of(tiny));
        assertNull(e.sentAs().of(absent));
        // Mutation: cut the stretch at the nearest character written -> "import json\nCONTACT = '<email_1>" said for a
        // text that ended inside the address.
    }

    @Test
    @DisplayName("one text, or none: sent differently in two requests -- the table learned a value between them -- what it was sent as is not known until the caller empties it; twice the same in one request is that text")
    void sentAsOneText() {
        var p = new Script();
        var gw = new CloudGateway(p, p, config(), x -> { }, null, new Redactor(null));
        String code = "import json\nNET = 'MyHomeNet'\n\ndef run(params):\n    return {'output': NET}";
        var e = declaring(Map.of(code, Redactor.Declared.CODE_AS_WRITTEN));
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user(request(code) + "\nAnd again:\n" + request(code))), plain(e));
        assertEquals(code, e.sentAs().of(code), "twice in the request, the same text each time");

        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user("my network is ssid=\"MyHomeNet\"\n" + request(code))), plain(e));
        assertNull(e.sentAs().of(code), "sent as it is, then with the network replaced: no one text");
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user(request(code))), plain(e));
        assertNull(e.sentAs().of(code), "and it stays unknown: the requests it was asked about are those");

        e.sentAs().forget();
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user(request(code))), plain(e));
        assertEquals(code.replace("MyHomeNet", "<ssid_1>"), e.sentAs().of(code), "emptied: what the requests since send");
        // Mutation: keep the last text said -> blocks written against the first request are applied to the second's.
    }

    @Test
    @DisplayName("what the filter writes where a stretch of text stood holds no line break -- a placeholder, a removed secret's marker, a vault value's -- so code sent with as many lines as it had has every line in its place, and a stretch over several lines taken out leaves fewer")
    void noLineBreakIsWritten() {
        var r = new Redactor(null);
        String code = "import json\nNET = 'ExampleHome5G'\nTO = 'reports@example.org'\nPIN = 'Xk9#mP2vQ7'\n"
                + "# set password=hunter2hunter2 in the config\nUA = 'Chrome/131.0.0.0'\n\ndef run(params):\n    return {}";
        r.filter("u1", "my network is ssid=\"ExampleHome5G\"", Map.of(), new Redactor.Tally());
        var said = new ArrayList<String>();
        r.filter("u1", request(code), Map.of("ROUTER_PIN", "Xk9#mP2vQ7"), new Redactor.Tally(),
                Map.of(code, Redactor.Declared.CODE_AS_WRITTEN), (was, sent) -> said.add(sent));
        String sent = said.getFirst();
        assertTrue(sent.contains("NET = '<ssid_1>'") && sent.contains("TO = '<email_1>'") && sent.contains("PIN = '«vault:ROUTER_PIN»'")
                && sent.contains("password=«secret removed»") && sent.contains("Chrome/<ip_1>"), "the premise: each kind written: " + sent);
        assertEquals(code.split("\n", -1).length, sent.split("\n", -1).length, "line for line");
        String[] was = code.split("\n", -1), now = sent.split("\n", -1);
        for (int i : new int[] {0, 6, 7, 8}) assertEquals(was[i], now[i], "line " + (i + 1) + " is itself");

        // A key over several lines goes out as one marker: fewer lines, and none has its place.
        String pem = code + "\nKEY = \"\"\"-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAAB\n"
                + "-----END OPENSSH PRIVATE KEY-----\"\"\"";
        said.clear();
        r.filter("u1", request(pem), Map.of(), new Redactor.Tally(), Map.of(pem, Redactor.Declared.CODE_AS_WRITTEN),
                (was2, sent2) -> said.add(sent2));
        assertTrue(said.getFirst().split("\n", -1).length < pem.split("\n", -1).length, said.getFirst());
        // Mutation: write a marker over two lines -> code sent with a key taken out has its lines again, each one off.
    }

    @Test
    @DisplayName("property: for a text declared anywhere in a part, what is said is exactly what stands in the filtered part where the text stood -- and telling changes nothing of what is sent")
    void saidIsWhatWasSent() {
        String[] pieces = {"my network is ssid=\"ExampleHome5G\" ", "mail ops0@example.com ", "NET = 'ExampleHome5G_x'\n",
                "import json\n", "password = 'hunter2hunter2'\n", "the marker <ssid_1> ", "GUEST = 'AP7'\n", "plain words ",
                "def run(params):\n", "    return {'output': 1}\n", "at 203.0.113.40 ", "ssid=\"AP7\" "};
        var random = new Random(11L);
        int said = 0, changed = 0;
        for (int round = 0; round < 3_000; round++) {
            var parts = new ArrayList<String>();
            for (int i = 6 + random.nextInt(8); i > 0; i--) parts.add(pieces[random.nextInt(pieces.length)]);
            // The declared text: some of the pieces in a row, between two characters no piece holds
            // and no detector reads, so where it stood can be seen in what was sent.
            int from = random.nextInt(parts.size() - 2), to = from + 2 + random.nextInt(parts.size() - from - 1);
            String declared = String.join("", parts.subList(from, to));
            if (declared.length() < 16) continue;
            String text = String.join("", parts.subList(0, from)) + "\u0001" + declared + "\u0002"
                    + String.join("", parts.subList(to, parts.size()));
            var kind = Redactor.Declared.values()[random.nextInt(Redactor.Declared.values().length)];
            var heard = new ArrayList<String[]>();

            String told = new Redactor(null).filter("u1", text, Map.of(), new Redactor.Tally(), Map.of(declared, kind),
                    (was, sent) -> heard.add(new String[] {was, sent}));
            String untold = new Redactor(null).filter("u1", text, Map.of(), new Redactor.Tally(), Map.of(declared, kind));

            assertEquals(untold, told, "telling changed what is sent: " + text);
            String between = told.substring(told.indexOf('\u0001') + 1, told.indexOf('\u0002'));
            // Where it stood between the marks; a piece row that repeats may stand elsewhere too.
            assertTrue(heard.stream().anyMatch(h -> h[0].equals(declared) && between.equals(h[1])),
                    kind + " " + text.replace("\n", "\\n") + " -> " + told.replace("\n", "\\n") + ": said "
                            + heard.stream().map(h -> String.valueOf(h[1]).replace("\n", "\\n")).toList());
            assertTrue(between.split("\n", -1).length <= declared.split("\n", -1).length, "a line was added: " + told);
            said++;
            if (!between.equals(declared)) changed++;
        }
        assertTrue(said > 2_500 && changed > 1_500, said + " said, " + changed + " of them changed on the way out");
        // Mutation: note where a stretch begins after its first piece is written -> what is said lacks its beginning.
    }
}
