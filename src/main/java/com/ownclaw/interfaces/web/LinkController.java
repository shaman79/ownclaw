package com.ownclaw.interfaces.web;

import com.ownclaw.users.CredentialVault;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Whether a link that opens the chat with a message in the box was signed by whoever holds the
 * account's link secret -- the guardian that sends alerts with such links. The page asks before it
 * shows where the message came from; sending it stays the owner's choice either way.
 * <p>
 * The link carries {@code exp} (unix seconds) and {@code sig}, the hex of
 * HMAC-SHA256(secret, exp + "\n" + task). The secret is {@value #SECRET_KEY} in the vault of the
 * account signed in: a link is verified only for an account that stores it, and the secret never
 * leaves the server. Nothing of the message is logged -- an alert names hosts and paths.
 */
@RestController
@RequestMapping("/api/links")
public class LinkController {

    private static final Logger log = LoggerFactory.getLogger(LinkController.class);

    /** The vault key the link secret is stored under ({@code /cred set GUARDIAN_LINK_SECRET ...}). */
    static final String SECRET_KEY = "GUARDIAN_LINK_SECRET";

    private final CredentialVault vault;
    private final LongSupplier nowSeconds;

    @Autowired
    public LinkController(CredentialVault vault) {
        this(vault, () -> System.currentTimeMillis() / 1000);
    }

    /** With the clock given: a test's. */
    LinkController(CredentialVault vault, LongSupplier nowSeconds) {
        this.vault = vault;
        this.nowSeconds = nowSeconds;
    }

    /**
     * {@code {task, exp, sig}} -> {@code {valid, expired, configured}}: whether the signature is
     * the secret's over this exp and task, whether exp has passed (or is not a time at all), and
     * whether this account stores a link secret -- without one, no link is valid.
     */
    @PostMapping("/verify")
    public Map<String, Object> verify(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
        String userId = (String) request.getAttribute("userId");
        Map<String, Object> given = body == null ? Map.of() : body;
        String task = given.get("task") instanceof String t ? t : null;
        String exp = given.get("exp") == null ? null : String.valueOf(given.get("exp"));
        String sig = given.get("sig") instanceof String s ? s : null;

        Optional<String> secret = vault.getCredential(userId, SECRET_KEY).filter(s -> !s.isEmpty());
        Long at = exp != null && exp.matches("[0-9]{1,18}") ? Long.parseLong(exp) : null;
        boolean expired = at == null || at < nowSeconds.getAsLong();
        boolean valid = secret.isPresent() && task != null && at != null && sig != null
                && signs(secret.get(), exp + "\n" + task, sig);
        log.info("Link checked for {}: valid={}, expired={}, secret stored={}", userId, valid, expired, secret.isPresent());
        return Map.of("valid", valid, "expired", expired, "configured", secret.isPresent());
    }

    /** Whether {@code sig} (hex, either case) is the HMAC-SHA256 of {@code text} under {@code secret}; compared in constant time. */
    static boolean signs(String secret, String text, String sig) {
        byte[] given;
        try {
            given = HexFormat.of().parseHex(sig.strip().toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)), given);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
