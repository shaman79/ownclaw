package com.ownclaw;

import com.ownclaw.privacy.VaultSecrets;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Forgets every vault value after each test. {@link VaultSecrets} is static, and a test that
 * stores a credential through a real vault fills it; left there, the values were scrubbed from
 * every later test's frames and rows, and a result depended on the order the tests ran in.
 */
public class VaultSecretsReset implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        VaultSecrets.clear();
    }
}
