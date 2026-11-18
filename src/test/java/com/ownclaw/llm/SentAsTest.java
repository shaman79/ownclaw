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

            var countedTold = new Redactor.Tally();
            var countedUntold = new Redactor.Tally();
            String told = new Redactor(null).filter("u1", text, Map.of(), countedTold, Map.of(declared, kind),
                    (was, sent) -> heard.add(new String[] {was, sent}));
            String untold = new Redactor(null).filter("u1", text, Map.of(), countedUntold, Map.of(declared, kind));

            assertEquals(untold, told, "telling changed what is sent: " + text);
            assertEquals(countedUntold.identifiersReplaced() + " replaced, " + countedUntold.secretsRemoved() + " removed",
                    countedTold.identifiersReplaced() + " replaced, " + countedTold.secretsRemoved() + " removed",
                    "telling changed what is counted: " + text);
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

    // ── what the review of the change asked for: the boundaries, the pairing, the memory ──

    static final String CODE = "import json\nNET = 'ExampleHome5G'\n\ndef run(params):\n    return {'output': NET}";

    @Test
    @DisplayName("a request the gateway refuses has sent nothing, and says nothing: what the code would have gone out as is not known")
    void aRefusedRequestSaysNothing() {
        var p = new Script();
        var gw = new CloudGateway(p, p, config(), x -> { }, null, new Redactor(null));
        String privateResult = "the private result of a tool: quarterly numbers 1234 5678 91011 and names of people";
        var index = new PrivateIndex();
        index.addPrivate(3, privateResult);
        var e = new EgressContext("u1", "t1", "codegen", index, Map.of(), null, null, null, Map.of(CODE, Redactor.Declared.CODE));
        assertThrows(EgressRefused.class,
                () -> gw.chat(List.of(LlmMessage.user("Fix:\n" + CODE + "\nresult:\n" + privateResult)), plain(e)));
        assertTrue(p.calls.isEmpty(), "the premise: nothing reached the provider");
        assertNull(e.sentAs().of(CODE), "said as sent, of a request that was refused");
        // Mutation: say it while the message is filtered, as the first version did -> the filtered code, 78 characters.
    }

    @Test
    @DisplayName("two declared texts in one part are each said as their own stretch -- the shorter that stands first too")
    void twoDeclaredTextsEachItsOwn() {
        var r = new Redactor(null);
        r.filter("u1", "my network is ssid=\"ExampleHome5G\"", Map.of(), new Redactor.Tally());
        String shorter = "GUEST = 'ExampleHome5G_guest'";
        var heard = new ArrayList<String[]>();
        String out = r.filter("u1", "A:\n" + shorter + "\nB:\n" + CODE + "\nend", Map.of(), new Redactor.Tally(),
                Map.of(shorter, Redactor.Declared.CODE, CODE, Redactor.Declared.CODE),
                (was, sent) -> heard.add(new String[] {was, sent}));
        assertEquals(2, heard.size());
        assertEquals(shorter, heard.get(0)[0]);
        assertEquals("GUEST = '<ssid_1>_guest'", heard.get(0)[1]);
        assertEquals(CODE, heard.get(1)[0]);
        assertEquals(CODE.replace("ExampleHome5G", "<ssid_1>"), heard.get(1)[1]);
        assertEquals("A:\n" + heard.get(0)[1] + "\nB:\n" + heard.get(1)[1] + "\nend", out);
        // Mutation: name the texts in the order they were looked for, the longer first -> each is said as the other's text.
    }

    @Test
    @DisplayName("a finding at the last character of a declared text, and one at its first, are part of what it is said as")
    void aFindingAtTheFirstAndAtTheLastCharacter() {
        var r = new Redactor(null);
        String endsWithAddress = "import json\n# written by ops0@example.com";
        String beginsWithAddress = "ops0@example.com wrote this code";
        var heard = new ArrayList<String[]>();
        String out = r.filter("u1", "```python\n" + endsWithAddress + "\n```\nand:" + beginsWithAddress + "\n", Map.of(),
                new Redactor.Tally(),
                Map.of(endsWithAddress, Redactor.Declared.CODE, beginsWithAddress, Redactor.Declared.OUTPUT),
                (was, sent) -> heard.add(new String[] {was, sent}));
        assertEquals("```python\nimport json\n# written by <email_1>\n```\nand:<email_1> wrote this code\n", out);
        assertEquals("import json\n# written by <email_1>", heard.get(0)[1]);
        assertEquals("<email_1> wrote this code", heard.get(1)[1]);
        // Mutation: the end noted a character early -> "...<email_1"; the beginning a character late -> "email_1> wrote...".
    }

    @Test
    @DisplayName("a finding that lies across the beginning of a declared text leaves it no beginning of its own: nothing is said")
    void aFindingAcrossTheBeginning() {
        var r = new Redactor(null);
        String startsInsideTheAddress = "example.com and the rest of the declared text";
        var heard = new ArrayList<String[]>();
        String out = r.filter("u1", "mail ops0@" + startsInsideTheAddress + "\n", Map.of(), new Redactor.Tally(),
                Map.of(startsInsideTheAddress, Redactor.Declared.OUTPUT), (was, sent) -> heard.add(new String[] {was, sent}));
        assertEquals("mail <email_1> and the rest of the declared text\n", out);
        assertEquals(1, heard.size());
        assertEquals(startsInsideTheAddress, heard.get(0)[0]);
        assertNull(heard.get(0)[1], "its beginning is inside the address's placeholder");
        // Mutation: take a finding that begins before the text for its beginning -> "<email_1> and the rest..." is said.
    }

    @Test
    @DisplayName("of two declared texts the vault scrub leaves the same, the one that sorts first is named -- as it was declared, not as the scrub left it")
    void theTwinThatSortsFirstAsDeclared() {
        var r = new Redactor(null);
        String open = String.valueOf((char) 0xAB), close = String.valueOf((char) 0xBB);
        Map<String, String> vault = Map.of("ROUTER_PIN", "Xk9#mP2vQ7");
        String withValue = "PIN = 'Xk9#mP2vQ7' and more of the code";
        String withMarker = "PIN = '" + open + "vault:ROUTER_PIN" + close + "' and more of the code";
        assertTrue(withValue.compareTo(withMarker) < 0, "the premise: the one with the value sorts first");
        var heard = new ArrayList<String[]>();
        r.filter("u1", "code:\n" + withValue + "\nend", vault, new Redactor.Tally(),
                Map.of(withValue, Redactor.Declared.CODE, withMarker, Redactor.Declared.CODE),
                (was, sent) -> heard.add(new String[] {was, sent}));
        assertEquals(1, heard.size());
        assertEquals(withValue, heard.get(0)[0]);
        assertEquals(withMarker, heard.get(0)[1]);
        heard.clear();
        r.filter("u1", "code:\n" + withValue + "\nend", vault, new Redactor.Tally(), Map.of(withValue, Redactor.Declared.CODE),
                (was, sent) -> heard.add(new String[] {was, sent}));
        assertEquals(withValue, heard.get(0)[0], "alone: named as declared, the value in it");
        // Mutation: name the twin that sorts last -> the one with the marker; name it as the scrub left it -> the same.
    }

    @Test
    @DisplayName("a text that went out once as it is and once across a finding, in one request, is not known")
    void untellableOnceIsNotKnown() {
        var p = new Script();
        var gw = new CloudGateway(p, p, config(), x -> { }, null, new Redactor(null));
        String cut = "import json\nCONTACT = 'ops0@example";
        var e = declaring(Map.of(cut, Redactor.Declared.CODE));
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user(cut + " and\n" + cut + ".com'\n")), plain(e));
        assertEquals(cut + " and\nimport json\nCONTACT = '<email_1>'\n", p.calls.getLast().getFirst().content(),
                "the premise: as it is, then with an address across its end");
        assertNull(e.sentAs().of(cut));
        // Mutation: a text that could not be told is not remembered -> the clean one is said.
    }

    @Test
    @DisplayName("telling counts what not telling counts")
    void tellingCountsTheSame() {
        var a = new Redactor(null);
        var b = new Redactor(null);
        for (var r : List.of(a, b)) r.filter("u1", "my network is ssid=\"ExampleHome5G\"", Map.of(), new Redactor.Tally());
        String code = "import json\nNET = 'ExampleHome5G_x'\nARCHIVE = '/tmp/ExampleHome5G_backup.tar'";
        var told = new Redactor.Tally();
        var untold = new Redactor.Tally();
        a.filter("u1", "```\n" + code + "\n```", Map.of(), told, Map.of(code, Redactor.Declared.CODE), (was, sent) -> { });
        b.filter("u1", "```\n" + code + "\n```", Map.of(), untold, Map.of(code, Redactor.Declared.CODE));
        assertEquals(2, untold.identifiersReplaced(), "the premise: two values inside longer words");
        assertEquals(untold.identifiersReplaced(), told.identifiersReplaced());
        assertEquals(untold.secretsRemoved(), told.secretsRemoved());
        // Mutation: the known values replaced inside a stretch counted apart when telling -> 0 for 2.
    }

    @Test
    @DisplayName("the memory: the same text again is that text, another or none is not known and stays so, forgetting empties it, and a context made of another's parts has its own")
    void theMemory() {
        var e = declaring(Map.of());
        var s = e.sentAs();
        s.put("a-text", "x");
        s.put("a-text", "x");
        assertEquals("x", s.of("a-text"));
        s.put("a-text", null);
        assertNull(s.of("a-text"));
        s.put("a-text", "x");
        assertNull(s.of("a-text"), "stays not known");
        s.put("b-text", null);
        s.put("b-text", "y");
        assertNull(s.of("b-text"));
        s.put(null, "z");
        assertNull(s.of(null));
        s.forget();
        s.put("a-text", "z");
        assertEquals("z", s.of("a-text"));
        var copy = new EgressContext(e.userId(), e.taskId(), e.purpose(), e.index(), e.secretValues(), e.allowed(),
                e.toolOf(), e.handedOut(), e.skillCode(), e.sentAs());
        assertNotSame(e.sentAs(), copy.sentAs());
        assertNull(copy.sentAs().of("a-text"));
        assertFalse(e.toString().contains("z"), "what was sent is in no log of the context");
        // Mutation: take the memory a caller passes -> the copy answers for the original's requests.
    }

    @Test
    @DisplayName("what is said is of a text standing as a stretch of its own: inside a longer declared text, and in a part the caller authored, it is read by other rules and not heard")
    void whereItIsNotHeard() {
        var p = new Script();
        var gw = new CloudGateway(p, p, config(), x -> { }, null, new Redactor(null));
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user("my network: ssid=\"ExampleHome5G\"")), plain(declaring(Map.of())));

        // Inside a longer declared text: the fence's stretch is said, the other occurrence is the longer text's.
        String written = "import json\nNET = '<ssid_1>'\n\ndef run(params):\n    return {'output': NET}";
        String longer = "Traceback of the load:\n" + written + "\nSyntaxError: nothing";
        var both = new java.util.LinkedHashMap<String, Redactor.Declared>();
        both.put(written, Redactor.Declared.CODE_AS_WRITTEN);
        both.put(longer, Redactor.Declared.OUTPUT);
        var e = declaring(both);
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.user("Broken code:\n```python\n" + written + "\n```\nError: " + longer + "\n")), plain(e));
        String sent = p.calls.getLast().getFirst().content();
        assertEquals(sent.substring(sent.indexOf("```python\n") + 10, sent.indexOf("\n```\n")), e.sentAs().of(written));

        // In an authored part the code goes with the vault's values out and nothing else done to it.
        var authored = declaring(Map.of(CODE, Redactor.Declared.CODE));
        p.next.add("ok");
        gw.chat(List.of(LlmMessage.authoredSystem("The code is:\n" + CODE), LlmMessage.user("Fix:\n" + CODE + "\nplease")),
                plain(authored));
        String system = p.calls.getLast().getFirst().content(), user = p.calls.getLast().getLast().content();
        assertTrue(system.contains(CODE) && !user.contains(CODE), "the premise: two texts of the code in one request");
        assertEquals(CODE.replace("ExampleHome5G", "<ssid_1>"), authored.sentAs().of(CODE), "the user part's");
    }
}
