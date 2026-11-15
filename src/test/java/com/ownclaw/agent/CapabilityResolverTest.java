package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.ContainerSandbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the resolver tells the model to build for a container run must be able to run there: the
 * container may connect, not capture (--cap-drop=NET_RAW), so no hint needs a raw or packet
 * socket.
 */
class CapabilityResolverTest {

    /** A resolver that sees a container runtime and no skills yet. */
    private static CapabilityResolver withContainer() {
        var container = new ContainerSandbox(new OwnClawConfig()) {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String runtime() {
                return "docker";
            }
        };
        return new CapabilityResolver(new ToolRegistry(List.of()), container);
    }

    @Test
    @DisplayName("a network scan in the container is an unprivileged one: TCP connect, no OS fingerprinting, no ARP ping")
    void aContainerScanIsUnprivileged() {
        var hint = withContainer().resolve("scan my network and find devices with open ports");
        assertNotNull(hint);
        assertEquals("network_scanning", hint.category());
        assertEquals(List.of("nmap", "net-tools"), hint.systemPackages());
        String d = hint.description();
        for (String said : List.of("always pass --unprivileged", "-sT -sV", "-Pn", "/proc/net/arp", "No -O, no -sS, no ARP ping")) {
            assertTrue(d.contains(said), "the hint does not say " + said + ": " + d);
        }
        for (String needsRaw : List.of("OS fingerprinting", "ping scan + ARP")) {
            assertFalse(d.contains(needsRaw), "the hint prescribes " + needsRaw + ": " + d);
        }
    }

    @Test
    @DisplayName("capturing packets is not something a skill can do: no hint sends the model to build one")
    void noPacketCapture() {
        for (String ask : List.of("capture packets on eth0", "run tcpdump for a minute", "sniff traffic on the LAN",
                "show me the network traffic")) {
            assertNull(withContainer().resolve(ask), ask);
        }
    }

    /** A resolver with no skills and no container: whatever sets a pattern off is a gap. */
    private static CapabilityResolver bare() {
        return new CapabilityResolver(new ToolRegistry(List.of()), null);
    }

    @Test
    @DisplayName("a trigger's words are found where a word begins, not inside another word or a skill's name")
    void triggersAreWordsNotSubstrings() {
        for (String message : List.of(
                // "port" in "report"
                "Open the attached file and send me a report.",
                // "new" in "news", "mail" in "email": a scheduled task that names its skills
                "Run daily_news_digest, then smtp_send_email with the result.",
                // "fetch" in "fetcher"
                "Run daily_menu_fetcher and email them to me.",
                // "trace" in "retrace"
                "Retrace the route we took last summer.")) {
            assertNull(bare().resolve(message), message);
        }
        // Mutation: words() without WORD_START -> each of these asks for a skill.
    }

    @Test
    @DisplayName("a trigger's words are all in one sentence: a word in the next sentence is another matter")
    void triggersStayInOneSentence() {
        assertNull(bare().resolve("Check the weather for tomorrow. Then send it to my email."));
        assertNull(bare().resolve("Scan this page for typos!\nOur network team will review it."));
        assertNull(bare().resolve("Check the logs. Mail them to Bob."));
        assertNull(bare().resolve("Is the backup done? I will check. The mail can wait"), "three sentences, a trigger's words in two of them");
        assertNotNull(bare().resolve("Please scan the 192.0.2.0/24 network for me."), "a dot inside a word ends no sentence");
        // Mutation: sentences() returning the whole text as one -> the first four ask for a skill.
    }

    @Test
    @DisplayName("where a sentence ends: at marks before a blank and at line breaks -- not at a dot inside a word, not at the dot of an abbreviation, a title or an initial")
    void whereASentenceEnds() {
        assertEquals(List.of("One", "Two", "Three", "Four"), sentencesOf("One. Two! Three?\nFour"));
        assertEquals(List.of("Wait", "go on", "really", "yes"), sentencesOf("Wait... go on?! really\r\n\r\nyes."));
        assertEquals(List.of("ping 192.0.2.7 and example.org now"), sentencesOf("ping 192.0.2.7 and example.org now"));
        assertEquals(List.of("....x ?!y"), sentencesOf("....x ?!y"), "marks with no blank after them end nothing");
        for (String one : List.of("check Mr. Smith's email", "Fetch approx. 10 emails", "check e.g. the email from Bob",
                "read the no. 3 email", "Zkontroluj např. e-maily od Jany", "dopis č. 3 od Jany", "Scan i.e. the home network")) {
            assertEquals(List.of(one), sentencesOf(one), "one request");
        }
        // What only looks like one: a letter, a unit, a noun that is also a title or a short form.
        assertEquals(List.of("Check plan B", "Email me the result"), sentencesOf("Check plan B. Email me the result"));
        assertEquals(List.of("Run it for 20 ms", "The command timed out"), sentencesOf("Run it for 20 ms. The command timed out"));
        assertEquals(List.of("I checked with the prof", "Mail me the notes"), sentencesOf("I checked with the prof. Mail me the notes"));
        assertEquals(List.of("Scan the doc", "Then tell the network team"), sentencesOf("Scan the doc. Then tell the network team"));
        assertEquals(List.of("Convert it for Dr. X", "The video is fine"), sentencesOf("Convert it for Dr. X. The video is fine"));
        assertEquals(List.of("I said no. 3 mails came"), sentencesOf("I said no. 3 mails came"), "the word \"no\" at a sentence's end, before a number, is read as the abbreviation: known, and left");
        assertEquals(List.of("I said no", "Three mails came"), sentencesOf("I said no. Three mails came"));
        assertEquals(List.of("Check it", "Email Bob"), sentencesOf("Check it. Email Bob"), "a short word is no abbreviation");
        assertEquals(List.of(), sentencesOf(" \n.\n!? "), "nothing but marks and breaks");
        assertEquals(List.of(), sentencesOf(""));
        // Mutations: the abbreviations not looked at -> each of the seven is two sentences; any
        // single letter, or a title in small letters, taken for one -> "Check plan B. Email me the
        // result" is one sentence, and asks for a mailbox; a mark with no blank after it taken for
        // an end -> "192" is a sentence.
    }

    private static List<String> sentencesOf(String text) {
        int[] bounds = CapabilityResolver.sentences(text);
        var out = new java.util.ArrayList<String>();
        for (int i = 0; i < bounds.length; i += 2) {
            String sentence = text.substring(bounds[i], bounds[i + 1]).strip();
            if (!sentence.isEmpty()) out.add(sentence);
        }
        return out;
    }

    @Test
    @DisplayName("what the patterns were written for still sets them off, stems and Czech included")
    void realRequestsStillMatch() {
        for (String message : List.of(
                "Scan my network and find devices with open ports",
                "check my email please",
                "Do I have any unread mail?",
                "Do I have any unread emails?",
                "any new email?",
                "Any new emails today?",
                "rescan the network please",
                "do a portscan of my network",
                "turn this html into a pdf",
                "Zkontroluj emaily",
                "P\u0159e\u010dti mi emaily",
                "st\u00e1hni emaily z imapu",
                "Naskenuj mi s\u00ed\u0165",
                "Give me a summary of my emails",
                "convert this video to mp4",
                "Run a traceroute to example.org",
                "What are the DNS records of example.org?",
                "check the ssl certificate of example.org",
                "show me the cpu usage",
                "run this command: uptime",
                "resize the image to 800 pixels",
                "Zkontroluj mail, pros\u00edm",
                "skenuj dom\u00e1c\u00ed s\u00ed\u0165",
                // What matched while a trigger's words were found anywhere, and was lost with that
                // for no reason: mail under another name, a word with a prefix, an abbreviation's dot.
                "check my gmail", "read my gmail", "any new gmail?", "fetch my webmail", "Zkontroluj gmail",
                "run a portscan on 192.0.2.10", "do a portscan of the router", "scan the subnetwork",
                "přeskenuj síť", "rerun the command", "check the openssl cert", "rediscover hosts",
                "check Mr. Smith's email", "Fetch approx. 10 emails", "Zkontroluj např. e-maily od Jany",
                "check e.g. the email from Bob", "read the no. 3 email", "Scan e.g. the home network",
                "check mr. smith's email", "check Dr. Novak's email", "check my calendar/email for today",
                "check slack/mail", "check my email, cc someone@example.org")) {
            assertNotNull(bare().resolve(message), message);
        }
        // Mutation: WORD_START also put after each word (whole words only) -> "emails", "records" fail.
    }

    @Test
    @DisplayName("an address or a host's name is no word for mail, and a sentence that only looks unfinished is finished: a task that sends something to someone@gmail.com asks for no mailbox")
    void addressesAndFinishedSentencesAskForNothing() {
        for (String message : List.of(
                "Check the weather for tomorrow and send it to someone@gmail.com",
                "Read this article and send the gist to a.friend@gmail.com",
                "Send the new price list to sales@gmail.com",
                "Write a summary of the meeting for someone@gmail.com",
                "Fetch the lunch menu and send it to me@gmail.com",
                "Check the build, then notify ops@webmail.example.org",
                "Read the changelog and post it to https://webmail.example.org/compose",
                "Fetch the report and upload it to mail.example.org",
                "Check the build and tell someone@mail.example.org",
                "Send the new price list to mail@example.org",
                "Read this and forward it to e-mail@example.org",
                "Check the build and tell email.alerts@example.org",
                // Two sentences, a trigger's words in each
                "Check plan B. Email me the result",
                "Scan page A. Network diagram is on page B",
                "Scan the doc. Then tell the network team",
                "Run it for 20 ms. The command timed out",
                "Read chapter 3 of the doc. My email is down",
                "I checked with the prof. Mail me the notes",
                "Convert it for Dr. X. The video is fine as it is")) {
            assertNull(bare().resolve(message), message);
        }
        // Mutations: the mail word found after an @ or a dot, before a dot and a letter, or as
        // an address's own name -> the first twelve ask for a mailbox reader; the abbreviation
        // rule as it was first written (any single letter, "doc", "ms", "prof" in small letters)
        // -> the rest ask.
    }

    @Test
    @DisplayName("mail to send is no mailbox to read: \"new\" is the word, not the start of \"news\"")
    void newsIsNotNewMail() {
        assertNull(bare().resolve("send me an email with the news"));
        assertNotNull(bare().resolve("is there new mail?"));
    }

    @Test
    @DisplayName("a long pasted line with no sentence end is read in time that grows with its length: it does not hold the task's start")
    void aLongLineIsLinear() {
        // A word that begins many triggers, every 66 characters, and never the word after it:
        // one regex per trigger, with a gap between its words, took over a minute on this.
        var line = new StringBuilder();
        while (line.length() < 400_000) line.append("open the valve a little and wait for the pressure to settle down ");
        String text = line.toString();
        var resolver = bare();
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(20),
                () -> assertNull(resolver.resolve(text)));
    }

    @Test
    @DisplayName("marks in a row, and very many short sentences, are read in time that grows with their number: forty thousand dots before a letter took half a minute")
    void marksInARowAreLinear() {
        var resolver = bare();
        String letter = "x";
        for (String text : List.of(
                ".".repeat(400_000) + letter,                         // a run that ends no sentence
                "?!".repeat(200_000) + letter,
                ("." .repeat(999) + letter).repeat(400),              // many such runs
                ". ".repeat(200_000),                                 // nothing but ends
                "open. ".repeat(60_000) + "scan? ".repeat(60_000),    // a trigger's first word in every sentence, never its second
                "no. 3 ".repeat(60_000) + "mr. ".repeat(60_000),      // every dot an abbreviation's: one long sentence
                "mail-".repeat(80_000) + letter,                      // a mail word at every hyphen, and never an @
                "check " + "mail.".repeat(80_000) + letter)) {        // ... at every dot
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(20),
                    () -> assertNull(resolver.resolve(text)), () -> "a text of " + text.length() + " characters beginning " + text.substring(0, 12));
        }
        // Mutation: the sentences found by the regex of before ([.!?]+ then a look ahead for a
        // blank) -> the first text alone takes minutes.
    }
}
