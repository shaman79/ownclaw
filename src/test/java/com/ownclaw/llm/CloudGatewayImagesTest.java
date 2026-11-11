package com.ownclaw.llm;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.privacy.PrivateIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gateway decides every image itself, by the file: it asks the image store for the copy of a
 * file of the task's user that was marked shown to the cloud, hashes what it read, and hands the
 * provider those bytes and no others -- whatever the message carried -- only where the filtered
 * message still places the image. Every image that left is on the ledger row; every one kept back
 * is too, with why.
 */
class CloudGatewayImagesTest {

    static final byte[] PIXELS = "PNG-PIXELS-OF-A-SCREENSHOT".getBytes(StandardCharsets.UTF_8);

    /** An image store with one shown image, f1 of u1, and one private file, f2 of u1. */
    static final class Store implements CloudImages {
        final List<String> asked = new ArrayList<>();
        byte[] bytes = PIXELS;
        String recorded = CloudGateway.sha256(PIXELS);

        public Found copyOf(String userId, String fileId) {
            asked.add(userId + "/" + fileId);
            if (!"u1".equals(userId)) return Found.withheld("it is not this user's file");
            if ("f1".equals(fileId)) return Found.of(new Copy("image/png", 1456, 819, bytes, recorded));
            return Found.withheld("it was not pasted as an image the cloud model is shown: it is private");
        }
    }

    private static OwnClawConfig config() {
        var c = new OwnClawConfig();
        c.getMentor().setProvider("anthropic");
        return c;
    }

    private static LlmRequestConfig cfg(String user) {
        return new LlmRequestConfig(null, null, false)
                .withEgress(new EgressContext(user, "t1", "think", new PrivateIndex(), Map.of(), (h, w) -> false, null));
    }

    /** The engine's first message: an image before the task's text, and the boundary after it. */
    static LlmMessage marked(List<LlmMessage.Image> images) {
        var sb = new StringBuilder("## Task\n");
        for (int i = 0; i < images.size(); i++) sb.append(LlmMessage.image(i));
        return LlmMessage.marked(sb.append("what does it say?").append(LlmMessage.CACHE_BOUNDARY)
                .append("\n\n---\n## Environment").toString(), images);
    }

    @Test
    @DisplayName("a pasted image of the task's user is handed over as the bytes the gateway read, and the ledger names it")
    void sentAndLedgered() {
        var provider = new CloudGatewayTest.Recording();
        var rows = new CloudGatewayTest.Rows();
        var store = new Store();
        var gw = new CloudGateway(provider, provider, config(), rows, null, new com.ownclaw.privacy.Redactor(null), store);

        gw.chat(List.of(LlmMessage.system("S"), marked(List.of(new LlmMessage.Image("f1", 1, 1, 1)))), cfg("u1"));

        LlmMessage.Image handed = provider.calls.get(0).get(1).images().get(0);
        assertNotNull(handed.sent(), "bytes attached");
        assertEquals(java.util.Base64.getEncoder().encodeToString(PIXELS), handed.sent().base64());
        assertEquals("image/png", handed.sent().mediaType());
        assertEquals(1456, handed.width(), "the copy's size, as recorded -- not the engine's word for it");
        assertEquals(List.of("u1/f1"), store.asked);
        var row = rows.last();
        assertEquals(EgressLedger.Decision.SENT, row.decision());
        assertEquals(List.of(new EgressLedger.Image("f1", "image/png", 1456, 819, PIXELS.length,
                CloudGateway.sha256(PIXELS).substring(0, 16))), row.images());
        assertTrue(row.withheld().isEmpty());
        long text = row.parts().stream().mapToLong(EgressLedger.Part::chars).sum();
        assertTrue(row.bytesOut() >= text + LlmMessage.Image.base64Bytes(PIXELS.length), "its base64 counted as egress");
        // Mutation: skip the ledger's images -> the owner's page cannot say an image left.
    }

    @Test
    @DisplayName("a private file, another user's file, or a copy changed since upload is not sent -- and the row says why")
    void refusedByTheFile() {
        var provider = new CloudGatewayTest.Recording();
        var rows = new CloudGatewayTest.Rows();
        var store = new Store();
        var gw = new CloudGateway(provider, provider, config(), rows, null, new com.ownclaw.privacy.Redactor(null), store);

        gw.chat(List.of(marked(List.of(new LlmMessage.Image("f2", 1, 1, 1)))), cfg("u1"));
        assertNull(provider.calls.get(0).get(0).images().get(0).sent(), "a private file's bytes never go");
        assertEquals("f2", rows.last().withheld().get(0).fileId());
        assertTrue(rows.last().withheld().get(0).why().contains("private"));
        assertTrue(rows.last().images().isEmpty());

        gw.chat(List.of(marked(List.of(new LlmMessage.Image("f1", 1, 1, 1)))), cfg("u2"));
        assertNull(provider.calls.get(1).get(0).images().get(0).sent(), "u1's image is not u2's to send");
        assertTrue(rows.last().withheld().get(0).why().contains("not this user's"));

        store.bytes = "OTHER-BYTES".getBytes(StandardCharsets.UTF_8);
        gw.chat(List.of(marked(List.of(new LlmMessage.Image("f1", 1, 1, 1)))), cfg("u1"));
        assertNull(provider.calls.get(2).get(0).images().get(0).sent(), "bytes that are not the copy recorded");
        assertTrue(rows.last().withheld().get(0).why().contains("not the bytes recorded"));
        // Mutation: drop the gateway's own hash -> a store that answers other bytes sends them.
    }

    @Test
    @DisplayName("bytes a message carries are never sent: the gateway reads its own, or sends none")
    void forgedBytesAreIgnored() {
        var provider = new CloudGatewayTest.Recording();
        var rows = new CloudGatewayTest.Rows();
        var gw = new CloudGateway(provider, provider, config(), rows, null, new com.ownclaw.privacy.Redactor(null), new Store());
        byte[] secret = "BYTES-OF-A-PRIVATE-STATEMENT".getBytes(StandardCharsets.UTF_8);
        var forged = new LlmMessage.Image("f2", 1, 1, secret.length, new LlmMessage.Sent("image/png", secret, "0"));
        gw.chat(List.of(marked(List.of(forged))), cfg("u1"));
        assertNull(provider.calls.get(0).get(0).images().get(0).sent(), "f2 is private: nothing goes for it");

        var forgedOverShown = new LlmMessage.Image("f1", 1, 1, secret.length, new LlmMessage.Sent("image/png", secret, "0"));
        gw.chat(List.of(marked(List.of(forgedOverShown))), cfg("u1"));
        assertEquals(java.util.Base64.getEncoder().encodeToString(PIXELS),
                provider.calls.get(1).get(0).images().get(0).sent().base64(), "f1's own copy, not what came with it");
        // Mutation: keep an incoming image's bytes -> the statement's bytes go as an "image".
    }

    @Test
    @DisplayName("only where the filtered message still places it, and only on the engine's marked message: the store is not even asked otherwise")
    void onlyWherePlaced() {
        var provider = new CloudGatewayTest.Recording();
        var rows = new CloudGatewayTest.Rows();
        var store = new Store();
        var gw = new CloudGateway(provider, provider, config(), rows, null, new com.ownclaw.privacy.Redactor(null), store);
        // A private key with no END line: the filter removes to the end of the text, the image's
        // marker and the boundary with it.
        String content = "## Task\nkey:\n-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA" + LlmMessage.image(0)
                + "look" + LlmMessage.CACHE_BOUNDARY + "step";
        gw.chat(List.of(LlmMessage.marked(content, List.of(new LlmMessage.Image("f1", 1, 1, 1)))), cfg("u1"));
        assertTrue(store.asked.isEmpty(), "an image the message no longer places is not looked up");
        assertNull(provider.calls.get(0).get(0).images().get(0).sent());
        assertFalse(rows.last().withheld().isEmpty(), "and the row says it was kept back");

        var plain = new LlmMessage(LlmMessage.Role.USER, "## Task\n" + LlmMessage.image(0) + "look"
                + LlmMessage.CACHE_BOUNDARY + "step", false, false, List.of(new LlmMessage.Image("f1", 1, 1, 1)));
        gw.chat(List.of(plain), cfg("u1"));
        assertTrue(store.asked.isEmpty(), "not the engine's message: no image is placed in it");
        assertNull(provider.calls.get(1).get(0).images().get(0).sent());
        // Mutation: resolve every image the message lists -> bytes go for an image no block holds.
    }

    @Test
    @DisplayName("the image's marker survives the filter beside the identifiers it rewrites: the image is still placed, and sent")
    void theMarkerSurvivesTheFilter() {
        var provider = new CloudGatewayTest.Recording();
        var rows = new CloudGatewayTest.Rows();
        var gw = new CloudGateway(provider, provider, config(), rows, null, new com.ownclaw.privacy.Redactor(null), new Store());
        // Digits and identifiers on both sides of the marker, which holds digits of its own.
        String content = "## Task\ncall +1 202 555 0143 or mail jan@example.com, card 4111 1111 1111 1111 at 12"
                + LlmMessage.image(0) + "4111 1111 1111 1111 and +1 202 555 0143 are on the screen, jan@example.com too"
                + LlmMessage.CACHE_BOUNDARY + "step";
        gw.chat(List.of(LlmMessage.marked(content, List.of(new LlmMessage.Image("f1", 1, 1, 1)))), cfg("u1"));
        LlmMessage handed = provider.calls.get(0).get(0);
        assertFalse(handed.content().contains("jan@example.com"), "the filter ran: " + handed.content());
        assertFalse(handed.content().contains("4111 1111"), handed.content());
        assertEquals(java.util.Set.of(0), LlmMessage.layout(handed.content()).pictures(), handed.content());
        assertNotNull(handed.images().get(0).sent(), "and the image went");
        assertTrue(rows.last().withheld().isEmpty(), rows.last().withheld().toString());
        // Mutation: a marker whose index runs into the text's digits -> a card or phone run takes it.
    }

    @Test
    @DisplayName("a call the gateway refuses sends no image, and its row names none as sent")
    void refusedCallSendsNone() {
        var provider = new CloudGatewayTest.Recording();
        var rows = new CloudGatewayTest.Rows();
        var gw = new CloudGateway(provider, provider, config(), rows, null, new com.ownclaw.privacy.Redactor(null), new Store());
        assertThrows(EgressRefused.class, () -> gw.chat(List.of(marked(List.of(new LlmMessage.Image("f1", 1, 1, 1)))),
                new LlmRequestConfig(null, null, false)));
        assertTrue(provider.calls.isEmpty());
        assertTrue(rows.last().images().isEmpty(), "unclassified: nothing went");
        assertEquals(0, rows.last().bytesOut());
    }
}
