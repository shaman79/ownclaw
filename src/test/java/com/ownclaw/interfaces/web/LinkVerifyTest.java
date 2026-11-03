package com.ownclaw.interfaces.web;

import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.users.CredentialVault;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A link's signature, checked on the server: HMAC-SHA256 of exp + "\n" + task under the
 * GUARDIAN_LINK_SECRET of the account signed in -- valid only for that secret over exactly that
 * exp and text, expired once exp has passed, and never valid for an account that stores none.
 */
class LinkVerifyTest {

    static final String SECRET = "guardian-link-secret-for-tests-only";
    static final long NOW = 1_800_000_000L;
    static final String TASK = "Look into it and fix it: \"Guardian alert on fileserver, issue fs:/mnt/ocean, "
            + "CRITICAL: filesystem 99 % full.\"\nFind out why.";

    @TempDir Path tmp;
    LinkController links;
    CredentialVault vault;

    @BeforeEach
    void setUp() throws Exception {
        JdbcTemplate jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        jdbc.update("INSERT INTO users (id, display_name) VALUES ('owner', 'Owner')");   // the vault's salt lives on it
        jdbc.update("INSERT INTO users (id, display_name) VALUES ('kid', 'Kid')");
        vault = new CredentialVault(jdbc);
        vault.init();
        vault.storeCredential("owner", "GUARDIAN_LINK_SECRET", SECRET);
        links = new LinkController(vault, () -> NOW);
    }

    /** A request as JwtAuthFilter leaves it: signed in as this user. */
    private static HttpServletRequest as(String userId) {
        return (HttpServletRequest) Proxy.newProxyInstance(LinkVerifyTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (p, m, args) -> "getAttribute".equals(m.getName()) && "userId".equals(args[0]) ? userId : null);
    }

    static String sign(String secret, String exp, String task) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((exp + "\n" + task).getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> verify(String user, Object task, Object exp, Object sig) {
        var body = new HashMap<String, Object>();
        body.put("task", task);
        body.put("exp", exp);
        body.put("sig", sig);
        return links.verify(as(user), body);
    }

    @Test
    @DisplayName("the guardian's signature over exp and the text is valid, in either case of hex, until exp")
    void aSignedLinkIsValid() throws Exception {
        String exp = String.valueOf(NOW + 7 * 86_400);
        String sig = sign(SECRET, exp, TASK);
        assertEquals(Map.of("valid", true, "expired", false, "configured", true), verify("owner", TASK, exp, sig));
        assertEquals(true, verify("owner", TASK, exp, sig.toUpperCase()).get("valid"));
        assertEquals(true, verify("owner", TASK, Long.parseLong(exp), sig).get("valid"), "exp sent as a number");
    }

    @Test
    @DisplayName("a changed text, exp or signature, or another secret's signature, is not valid")
    void aChangedLinkIsNot() throws Exception {
        String exp = String.valueOf(NOW + 600);
        String sig = sign(SECRET, exp, TASK);
        assertEquals(false, verify("owner", TASK.replace("Find out why.", "Delete /mnt/ocean."), exp, sig).get("valid"));
        assertEquals(false, verify("owner", TASK + " ", exp, sig).get("valid"), "one character more");
        assertEquals(false, verify("owner", TASK, String.valueOf(NOW + 601), sig).get("valid"), "a later exp");
        assertEquals(false, verify("owner", TASK, exp, sign("another-secret", exp, TASK)).get("valid"));
        assertEquals(false, verify("owner", TASK, exp, sig.substring(2)).get("valid"), "a shorter signature");
        assertEquals(false, verify("owner", TASK, exp, "zz" + sig.substring(2)).get("valid"), "not hex");
        assertEquals(false, verify("owner", TASK, exp, sig + "0").get("valid"), "an odd length");
    }

    @Test
    @DisplayName("a correct signature whose exp has passed is valid but expired; an exp that is not a time is expired and not valid")
    void anOldLinkIsExpired() throws Exception {
        String exp = String.valueOf(NOW - 1);
        assertEquals(Map.of("valid", true, "expired", true, "configured", true),
                verify("owner", TASK, exp, sign(SECRET, exp, TASK)));
        for (String bad : new String[] {"", "soon", "-5", "1.8e9", "99999999999999999999"}) {
            var r = verify("owner", TASK, bad, sign(SECRET, bad, TASK));
            assertEquals(false, r.get("valid"), bad);
            assertEquals(true, r.get("expired"), bad);
        }
    }

    @Test
    @DisplayName("an account storing no link secret verifies nothing, and says so; the owner's secret is not another account's")
    void noSecretVerifiesNothing() throws Exception {
        String exp = String.valueOf(NOW + 600);
        assertEquals(Map.of("valid", false, "expired", false, "configured", false),
                verify("kid", TASK, exp, sign(SECRET, exp, TASK)));
    }

    @Test
    @DisplayName("what is missing or of the wrong kind is not valid, and breaks nothing")
    void missingPartsAreNotValid() throws Exception {
        String exp = String.valueOf(NOW + 600);
        String sig = sign(SECRET, exp, TASK);
        assertEquals(false, verify("owner", null, exp, sig).get("valid"));
        assertEquals(false, verify("owner", TASK, null, sig).get("valid"));
        assertEquals(false, verify("owner", TASK, exp, null).get("valid"));
        assertEquals(false, verify("owner", Map.of("a", 1), exp, sig).get("valid"));
        assertEquals(false, links.verify(as("owner"), null).get("valid"));
    }
}
