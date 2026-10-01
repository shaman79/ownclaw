package com.ownclaw.privacy;

import com.ownclaw.conversation.MigratedDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The privacy filter's detectors, one form at a time, what they must leave alone, and the
 * placeholder table both ways. Every value here is made up: example.org addresses, documentation
 * MACs (00:00:5e:00:53:xx), fictional phone numbers, the published example IBAN and test cards,
 * and two public resolvers for the public addresses.
 */
class RedactorTest {

    /** One text and the one value in it the filter must take out: a secret ({@code kind} null) or an identifier. */
    record Case(String text, String value, Redactor.Kind kind) {}

    static Case secret(String text, String value) { return new Case(text, value, null); }

    static Case id(String text, String value, Redactor.Kind kind) { return new Case(text, value, kind); }

    static final List<Case> FOUND = List.of(
            // secrets by the name they are written under
            secret("\toption key 'fake-wifi-key-1'", "fake-wifi-key-1"),
            secret("\toption sae_password \"fake-sae-pass\"", "fake-sae-pass"),
            secret("wireless.default_radio0.key='fake-wifi-key-2'", "fake-wifi-key-2"),
            secret("wpa_passphrase=fake-hostapd-pass", "fake-hostapd-pass"),
            secret("\tpsk=\"fake-supplicant-psk\"", "fake-supplicant-psk"),
            secret("DB_PASSWORD=fake-env-pass", "fake-env-pass"),
            secret("export API_TOKEN=\"fake-env-token\"", "fake-env-token"),
            secret("smtp_pass = fake-ini-pass", "fake-ini-pass"),
            secret("{\"user\":\"admin\",\"password\":\"fake-json-pass\"}", "fake-json-pass"),
            secret("{'client_secret': 'fake-dict-secret'}", "fake-dict-secret"),
            secret("  apiKey: \"fake-camel-key\"", "fake-camel-key"),
            secret("password: fake-yaml-pass", "fake-yaml-pass"),
            secret("- token: fake-yaml-token", "fake-yaml-token"),
            secret("Authorization: Bearer fakeBearerToken0001", "fakeBearerToken0001"),
            secret("GET https://api.example.org/v1/items?access_token=fakeQueryToken&page=2", "fakeQueryToken"),
            secret("mysql --password=fake-flag-pass -h db", "fake-flag-pass"),
            secret("connect(host, password=\"fake-kwarg-pass\")", "fake-kwarg-pass"),
            // ... with an apostrophe written the way UCI and the shell write one
            secret("\toption key 'Don'\\''t-guess-fake-77'", "t-guess-fake-77"),
            secret("wireless.default_radio0.key='Don'\\''t-guess-fake-78'", "t-guess-fake-78"),
            // ... unquoted, blanks and all, where a machine wrote the line
            secret("wpa_passphrase=fake horse battery staple", "fake horse battery staple"),
            secret("802-11-wireless-security.psk:           fake horse battery staple", "fake horse battery staple"),
            secret("PASSWORD=fake-env-pass-2   # router admin", "fake-env-pass-2"),
            // ... in the middle of a line
            secret("2026-09-29 10:00:01 login ok user=root password=fake-logfmt-pass from 10.0.0.10", "fake-logfmt-pass"),
            secret("Environment=\"API_TOKEN=fake-systemd-token\"", "fake-systemd-token"),
            secret("Environment=OLLAMA_HOST=0.0.0.0 DB_PASSWORD=fake-systemd-pass", "fake-systemd-pass"),
            secret("                \"MYSQL_ROOT_PASSWORD=fake-docker-pass\",", "fake-docker-pass"),
            secret("uci set wireless.default_radio0.key=fake-uci-set-key", "fake-uci-set-key"),
            secret("> Authorization: Basic ZmFrZTpmYWtlLWJhc2lj", "ZmFrZTpmYWtlLWJhc2lj"),
            secret("curl -H 'Authorization: Bearer fakeMidLineToken01' https://api.example.org", "fakeMidLineToken01"),
            secret("< Set-Cookie: sysauth_https=fakefakefakefakefakefakefake0002; path=/cgi-bin/luci/",
                    "fakefakefakefakefakefakefake0002"),
            // ... under names of keys, sessions and numbered keys
            secret("network.wgpeer1.preshared_key='RmFrZVByZXNoYXJlZEtleUZvclRlc3RzMDAwMDA='",
                    "RmFrZVByZXNoYXJlZEtleUZvclRlc3RzMDAwMDA="),
            secret("PresharedKey = RmFrZVByZXNoYXJlZEtleUZvclRlc3RzMDAwMDE=", "RmFrZVByZXNoYXJlZEtleUZvclRlc3RzMDAwMDE="),
            secret("\toption key1 's:Fake1'", "s:Fake1"),
            secret("{\"result\":[0,{\"ubus_rpc_session\":\"fakefakefakefakefakefakefake0001\"}]}",
                    "fakefakefakefakefakefakefake0001"),
            // ... as command-line flags and Kubernetes pairs
            secret("root 1 0 /usr/bin/backup-agent --user admin --password fake-ps-pass --host 10.0.0.10", "fake-ps-pass"),
            secret("Example: curl -u root:fake-curl-pass http://10.0.0.1/", "fake-curl-pass"),
            secret("        - name: DB_PASSWORD\n          value: fake-k8s-pass", "fake-k8s-pass"),
            // ... in a URL, a WiFi QR code
            secret("DATABASE_URL=postgres://app:fake-db-pass@db:5432/app", "fake-db-pass"),
            secret("REDIS_URL=redis://:fake-redis-pass@cache:6379/0", "fake-redis-pass"),
            secret("url: http://root:fake-url-pass@10.0.0.1/ubus", "fake-url-pass"),
            secret("QR payload: WIFI:T:WPA;P:fake-qr-pass;;", "fake-qr-pass"),
            // secrets by their shape
            secret("key:\n-----BEGIN OPENSSH PRIVATE KEY-----\nRkFLRUtFWU1BVEVSSUFM\n-----END OPENSSH PRIVATE KEY-----\nend",
                    "RkFLRUtFWU1BVEVSSUFM"),
            secret("cut off: -----BEGIN RSA PRIVATE KEY-----\nRkFLRUhBTEZLRVk=", "RkFLRUhBTEZLRVk="),
            secret("root:$6$fakesalt$FakeHashValueForTestsOnly000:19000:0:99999:7:::",
                    "$6$fakesalt$FakeHashValueForTestsOnly000"),
            secret("md5 $1$fakesalt$FakeMd5HashValue00000", "$1$fakesalt$FakeMd5HashValue00000"),
            secret("hash $2b$12$FakeFakeFakeFakeFakeFakeFakeFakeFakeFakeFakeFakeFake0 ok",
                    "$2b$12$FakeFakeFakeFakeFakeFakeFakeFakeFakeFakeFakeFakeFake0"),
            secret("hash $y$j9T$fakesalt$FakeYescryptHashValue000 ok", "$y$j9T$fakesalt$FakeYescryptHashValue000"),
            secret("hash $argon2id$v=19$m=65536,t=3,p=4$ZmFrZXNhbHQ$FakeArgonHashValue0000 ok",
                    "$argon2id$v=19$m=65536,t=3,p=4$ZmFrZXNhbHQ$FakeArgonHashValue0000"),
            secret("jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.ZmFrZS1zaWduYXR1cmU",
                    "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.ZmFrZS1zaWduYXR1cmU"),
            secret("key sk-ant-api03-FAKEFAKEFAKEFAKEFAKE0000 here", "sk-ant-api03-FAKEFAKEFAKEFAKEFAKE0000"),
            secret("key sk-proj-FakeFakeFake0000FakeFake here", "sk-proj-FakeFakeFake0000FakeFake"),
            secret("key ghp_FakeFakeFakeFakeFakeFakeFakeFake0000 here", "ghp_FakeFakeFakeFakeFakeFakeFakeFake0000"),
            secret("key github_pat_FakeFakeFakeFakeFake00000 here", "github_pat_FakeFakeFakeFakeFake00000"),
            secret("key xoxb-0000000000-FAKEFAKEFAKE here", "xoxb-0000000000-FAKEFAKEFAKE"),
            secret("key AKIAFAKEFAKEFAKE0000 here", "AKIAFAKEFAKEFAKE0000"),
            secret("key AIzaFakeFakeFakeFakeFakeFakeFakeFake000 here", "AIzaFakeFakeFakeFakeFakeFakeFakeFake000"),
            secret("admin:$apr1$fakesalt$FakeApr1HashValue000/", "$apr1$fakesalt$FakeApr1HashValue000/"),
            secret("GET https://api.telegram.org/bot123456789:FAKEfakeFAKEfakeFAKEfakeFAKEfake123/getUpdates",
                    "FAKEfakeFAKEfakeFAKEfakeFAKEfake123"),
            // identifiers
            id("mail alice@example.org today", "alice@example.org", Redactor.Kind.EMAIL),
            id("call +1 202 555 0143 now", "+1 202 555 0143", Redactor.Kind.PHONE),
            id("call +44 (20) 7946-0958 now", "+44 (20) 7946-0958", Redactor.Kind.PHONE),
            id("IBAN DE89 3704 0044 0532 0130 00 please", "DE89 3704 0044 0532 0130 00", Redactor.Kind.IBAN),
            id("IBAN GB82WEST12345698765432.", "GB82WEST12345698765432", Redactor.Kind.IBAN),
            id("card 4111 1111 1111 1111 expires", "4111 1111 1111 1111", Redactor.Kind.CARD),
            id("card 378282246310005 expires", "378282246310005", Redactor.Kind.CARD),
            id("card 5500-0000-0000-0004 expires", "5500-0000-0000-0004", Redactor.Kind.CARD),
            id("station 00:00:5E:00:53:01 joined", "00:00:5E:00:53:01", Redactor.Kind.MAC),
            id("station 00-00-5e-00-53-02 joined", "00-00-5e-00-53-02", Redactor.Kind.MAC),
            id("resolver 9.9.9.9 answered", "9.9.9.9", Redactor.Kind.IP),
            id("upstream 1.1.1.1:53 answered", "1.1.1.1", Redactor.Kind.IP),
            id("upstream [2606:4700:4700::1111]:53 answered", "2606:4700:4700::1111", Redactor.Kind.IP),
            id("\toption ssid 'Fake Kolibri 5G'", "Fake Kolibri 5G", Redactor.Kind.SSID),
            id("wireless.guest.ssid='Fake-Guest'", "Fake-Guest", Redactor.Kind.SSID),
            id("ssid=Fake Office WiFi", "Fake Office WiFi", Redactor.Kind.SSID),
            id("\tSSID: Fake Neighbour", "Fake Neighbour", Redactor.Kind.SSID),
            id("wlan0     ESSID: \"Fake-Essid\"", "Fake-Essid", Redactor.Kind.SSID),
            id("{\"ssid\":\"Fake-Json-Net\",\"encryption\":\"psk2\"}", "Fake-Json-Net", Redactor.Kind.SSID),
            id("\toption ssid 'Fake'\\''s Net'", "Fake'\\''s Net", Redactor.Kind.SSID),
            id("\t\tssid Fake-Iw-Net", "Fake-Iw-Net", Redactor.Kind.SSID),
            id("ssid2=\"Fake-Ssid2\"", "Fake-Ssid2", Redactor.Kind.SSID),
            id("QR payload: WIFI:T:WPA;S:Fake-QR-Net;;", "Fake-QR-Net", Redactor.Kind.SSID),
            id("\toption hostname 'fake-desktop'", "fake-desktop", Redactor.Kind.HOST),
            id("config host\n\toption name 'fake-laptop'\n\toption ip '10.0.0.20'", "fake-laptop", Redactor.Kind.HOST),
            id("dhcp.@host[0].name='fake-printer'", "fake-printer", Redactor.Kind.HOST),
            id("{\"hostname\": \"fake-phone\", \"ip\": \"10.0.0.31\"}", "fake-phone", Redactor.Kind.HOST),
            id("1727777777 00:00:5e:00:53:10 10.0.0.40 fake-tablet *", "fake-tablet", Redactor.Kind.HOST),
            id("# br-lan 0001000127aabbccdd 1a2b3c4d fake-nas 1727777777 1f 128 fd00::1f/128", "fake-nas",
                    Redactor.Kind.HOST),
            id("fake-iphone.lan (10.0.0.23) at 00:00:5e:00:53:10 [ether]  on br-lan", "fake-iphone.lan",
                    Redactor.Kind.HOST),
            id("Sep 29 10:00:01 dnsmasq-dhcp[1234]: DHCPACK(br-lan) 10.0.0.23 00:00:5e:00:53:10 fake-iphone",
                    "fake-iphone", Redactor.Kind.HOST),
            id("GET /unsubscribe?email=jan.fake%40example.org HTTP/1.1", "jan.fake%40example.org", Redactor.Kind.EMAIL),
            id("{\"contact\": \"jan.fake\\u0040example.org\"}", "jan.fake\\u0040example.org", Redactor.Kind.EMAIL));

    @Test
    @DisplayName("every secret form is removed and every identifier kind replaced, one each")
    void everyFormIsFound() {
        for (Case c : FOUND) {
            var tally = new Redactor.Tally();
            String out = new Redactor(null).filter("u1", c.text(), Map.of(), tally);
            assertFalse(out.contains(c.value()), "left in: " + c.text() + " -> " + out);
            if (c.kind() == null) {
                assertTrue(out.contains(Redactor.SECRET_REMOVED), out);
                assertEquals(1, tally.secretsRemoved(), "one secret in: " + c.text() + " -> " + out);
                assertEquals(0, tally.identifiersReplaced(), out);
            } else {
                assertEquals(c.text().replace(c.value(), "<" + c.kind().tag() + "_1>")
                                // the lease line's MAC is an identifier of its own
                                .replace("00:00:5e:00:53:10", "<mac_1>"),
                        out, "the identifier and nothing else: " + c.text());
                assertEquals(0, tally.secretsRemoved(), out);
            }
        }
    }

    /** {@code a.b.c.d}: private addresses written as numbers, the way a test names a range. */
    static String ip(int a, int b, int c, int d) {
        return a + "." + b + "." + c + "." + d;
    }

    @Test
    @DisplayName("what is no secret and no identifier goes as it is")
    void falsePositives() {
        var texts = new ArrayList<>(List.of(
                "listening on port 8080, version 1.2.3, Python 3.11.4, kernel 5.15.0-91-generic",
                "Chrome/120.0.6099.109 and Windows 10.0.19045.3803",
                "size 4,096 MB, 1048576 bytes, 7.5 GiB free",
                "2026-10-01T12:00:00+02:00 and 2026-10-01 12:34:56 and 30.09.2026 at 12:34",
                "epoch 1727777777, in ms 1727777777777",
                "uuid 550e8400-e29b-41d4-a716-446655440000",
                "sha256 e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 md5 d41d8cd98f00b204e9800998ecf8427e commit 6a22dbc",
                "price $1,234.56, 1 234,56 Kč, € 99.90, total 12500.00 CZK",
                "192.0.2.10 198.51.100.7 203.0.113.9 fe80::1 fd00::1 2001:db8::1 ::1 255.255.255.0 224.0.0.251 0.0.0.0",
                "\toption encryption 'psk2'\n\toption channel '36'\n\toption auth_server '10.0.0.2'\n\toption auth_port '1812'",
                "token_type=Bearer, tokens: 1234, max_tokens = 4096, promptTokens: 300",
                "a table: 45 23 67 12 89 34 56 78 90 12",
                "+0200 and +12:00 and 1+2=3",
                "    password = os.environ[\"SMTP_PASSWORD\"]\n    token = None\n    def login(self, password: str):",
                "    headers = {\"Authorization\": \"Bearer \" + token}\n    password = args.password\n    pw = getpass()",
                "  credentials: comma-separated vault keys, auto-injected as env vars. NEVER pass values directly.",
                "Enter your password:\npassword: str\nsecret = \"{secret}\"\nAPI_KEY=${API_KEY}",
                "email the report @ noon; npm i pkg@1.2.3; @decorator",
                "-----BEGIN CERTIFICATE-----\nMIIBkTCB+wIJAKHHIG\n-----END CERTIFICATE-----",
                "shell: echo $1$2$3 and price $5$",
                // paging cursors, a public key, a GPIO pin, code that names a variable, a uid:gid,
                // a help text's placeholder, and a word in brackets that is no address
                "{\"files\": [{\"name\": \"a.txt\"}], \"nextPageToken\": \"CAoQAA\"}",
                "{\"IsTruncated\": true, \"NextContinuationToken\": \"1ueGcxLPRx1Tr\"}",
                "PublicKey = RmFrZVB1YmxpY0tleUZvclRlc3RzMDAwMDAwMDA=",
                "  pin: 17\n  mode: output",
                "    client.login(user=user, password=password)",
                "docker run -u 1000:1000 image",
                "  --password PASSWORD   the router's admin password\n  --token-file PATH",
                "Lunch (tomorrow) at noon"));
        for (int[] o : new int[][] {{10, 0, 0, 1}, {10, 255, 3, 7}, {172, 16, 5, 4}, {172, 31, 255, 254},
                {192, 168, 1, 20}, {100, 64, 0, 1}, {100, 127, 255, 254}, {127, 0, 0, 1}, {169, 254, 1, 1}}) {
            texts.add("gateway " + ip(o[0], o[1], o[2], o[3]) + " is up");
        }
        for (String text : texts) {
            var tally = new Redactor.Tally();
            assertEquals(text, new Redactor(null).filter("u1", text, Map.of(), tally));
            assertFalse(tally.any(), text);
        }
        // Mutations: no range check on IPv4 -> the private gateways become <ip_n>; no issuer
        // check on cards -> the epoch in ms; no expression guard -> the code lines lose values.
    }

    @Test
    @DisplayName("a vault value is removed as «vault:KEY», wherever it is, and counted")
    void vaultValues() {
        var tally = new Redactor.Tally();
        String out = new Redactor(null).filter("u1", "login with fake-vault-value-9 on port 465, then fake-vault-value-9",
                Map.of("ROUTER_PASS", "fake-vault-value-9", "SHORT", "465"), tally);
        assertEquals("login with «vault:ROUTER_PASS» on port 465, then «vault:ROUTER_PASS»", out);
        assertEquals(2, tally.secretsRemoved());
        assertEquals(2, new Redactor(null).count("u1", "fake-vault-value-9 fake-vault-value-9",
                Map.of("ROUTER_PASS", "fake-vault-value-9")).secretsRemoved());
    }

    @Test
    @DisplayName("a vault value under eight characters is removed where it stands as a word, and only there")
    void shortVaultValues() {
        var tally = new Redactor.Tally();
        String out = new Redactor(null).filter("u1",
                "Command '['sshpass', '-p', 'admin12', 'ssh']' failed; admin123, xadmin12 and ADMIN12_X are other words; ok: true",
                Map.of("ROUTER_PASS", "admin12", "SMTP_AUTH", "true", "SHORT_PIN", "12"), tally);
        assertEquals("Command '['sshpass', '-p', '«vault:ROUTER_PASS»', 'ssh']' failed; admin123, xadmin12 and "
                + "ADMIN12_X are other words; ok: true", out, "a word every JSON holds, or two characters, is not removed");
        assertEquals(1, tally.secretsRemoved());
        assertEquals(-1, Redactor.vaultValueAt("the admin12_x key", "admin12", 0));
        assertEquals(4, Redactor.vaultValueAt("pin 4821.", "4821", 0));
    }

    @Test
    @DisplayName("one rule names a secret, for the vault's keys as for what a skill prints")
    void secretNames() {
        for (String name : List.of("WIFI_PSK", "WG_PRIVATE_KEY", "SSH_KEY", "WIFI_KEY", "ROUTER_PIN", "SMTP_PASS",
                "IMAP_PASSWORD", "TELEGRAM_BOT_TOKEN", "OPENAI_API_KEY", "key1", "sysauth", "ubus_rpc_session",
                "Set-Cookie", "PresharedKey")) {
            assertTrue(Redactor.isSecretName(name), name);
        }
        for (String name : List.of("ROUTER_HOST", "SMTP_USER", "SMTP_HOST", "nextPageToken", "NextContinuationToken",
                "PublicKey", "pin", "auth_server", "max_tokens", "key_mgmt")) {
            assertFalse(Redactor.isSecretName(name), name);
        }
    }

    @Test
    @DisplayName("a value written with a quoted string's escapes is kept as it reads, and found again as json.dumps writes it")
    void escapedValuesAreKeptAsTheyRead() {
        var redactor = new Redactor(null);
        assertEquals("{\"ssid\": \"<ssid_1>\", \"clients\": 3}",
                redactor.filter("u1", "{\"ssid\": \"Kav\\u00e1rna Fake\", \"clients\": 3}", Map.of(), new Redactor.Tally()));
        assertEquals("connect to Kavárna Fake", redactor.restore("u1", "connect to <ssid_1>"));
        assertEquals("{\"network\": \"<ssid_1>\"} and <ssid_1>",
                redactor.filter("u1", "{\"network\": \"Kav\\u00e1rna Fake\"} and Kavárna Fake", Map.of(), new Redactor.Tally()),
                "the same value, written either way");
        assertEquals("\toption ssid '<ssid_2>'",
                redactor.filter("u1", "\toption ssid 'Fake'\\''s Net'", Map.of(), new Redactor.Tally()));
        assertEquals("Fake's Net", redactor.restore("u1", "<ssid_2>"));
    }

    @Test
    @DisplayName("a placeholder that is a whole quoted string comes back between the other quotes when its value holds the one")
    void restoredQuotesStayCode() {
        var redactor = new Redactor(null);
        redactor.filter("u1", "{\"ssid\": \"Fake Jana's WiFi\"}", Map.of(), new Redactor.Tally());
        assertEquals("NETWORK = \"Fake Jana's WiFi\"", redactor.restore("u1", "NETWORK = '<ssid_1>'"));
        assertEquals("NETWORK = \"Fake Jana's WiFi\"", redactor.restore("u1", "NETWORK = \"<ssid_1>\""));
        assertEquals("on Fake Jana's WiFi.", redactor.restore("u1", "on <ssid_1>."));
    }

    @Test
    @DisplayName("text in a placeholder's shape goes as a placeholder of its own, and a reply quoting it gets the text back")
    void placeholderShapedTextRoundTrips() {
        var redactor = new Redactor(null);
        redactor.filter("u1", "Owner: owner@example.org", Map.of(), new Redactor.Tally());
        var tally = new Redactor.Tally();
        assertEquals("for example <text_1>.", redactor.filter("u1", "for example <email_1>.", Map.of(), tally));
        assertFalse(tally.any(), "no identifier, no secret");
        assertEquals("for example <email_1>.", redactor.restore("u1", "for example <text_1>."));
        assertEquals("owner@example.org", redactor.restore("u1", "<email_1>"));
    }

    @Test
    @DisplayName("a removed secret is found in a call's arguments, keys and values, at any depth")
    void removedSecretsAreFound() {
        assertTrue(Redactor.holdsRemovedSecret(Map.of("config", List.of("option key '" + Redactor.SECRET_REMOVED + "'"))));
        assertTrue(Redactor.holdsRemovedSecret(Map.of("x", Map.of("«vault:ROUTER_PASS»", 1))));
        assertTrue(Redactor.holdsRemovedSecret("pw = '«vault:ROUTER_PASS»'"));
        assertFalse(Redactor.holdsRemovedSecret(Map.of("ssid", "Fake Home", "n", 3)));
    }

    @Test
    @DisplayName("the same value gets the same placeholder across requests, users apart, and after a restart")
    void placeholdersAreStable(@TempDir Path tmp) throws Exception {
        var db = MigratedDatabase.at(tmp.resolve("t.db"));
        var redactor = new Redactor(db);
        String first = redactor.filter("u1", "ssid=Fake Home\nmail alice@example.org", Map.of(), new Redactor.Tally());
        assertEquals("ssid=<ssid_1>\nmail <email_1>", first);
        String second = redactor.filter("u1", "mail bob@example.org and alice@example.org", Map.of(), new Redactor.Tally());
        assertEquals("mail <email_2> and <email_1>", second, "the same value, the same placeholder");
        assertEquals("mail <email_1>", redactor.filter("u2", "mail bob@example.org", Map.of(), new Redactor.Tally()),
                "each user numbers his own");

        var restarted = new Redactor(db);
        assertEquals(second, restarted.filter("u1", "mail bob@example.org and alice@example.org", Map.of(),
                new Redactor.Tally()), "the table outlives the process");
        assertEquals("send to alice@example.org on Fake Home",
                restarted.restore("u1", "send to <email_1> on <ssid_1>"));
        assertEquals("send to <email_1>", restarted.restore("u3", "send to <email_1>"), "another user's table");
        assertEquals("<email_9> <nonsense_1> <ssid_x>", restarted.restore("u1", "<email_9> <nonsense_1> <ssid_x>"),
                "a placeholder the table does not know stays as written");
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM privacy_placeholders WHERE user_id = 'u1'",
                Integer.class));
    }

    @Test
    @DisplayName("an SSID or hostname, once found by its name, is replaced wherever it stands as a word")
    void knownNamesAreReplacedAnywhere() {
        var redactor = new Redactor(null);
        assertEquals("ssid=<ssid_1>\nhostname=<host_1>\nhostname=<host_2>",
                redactor.filter("u1", "ssid=Fake Home\nhostname=fake-laptop\nhostname=nas", Map.of(), new Redactor.Tally()));
        var tally = new Redactor.Tally();
        assertEquals("Moved <host_1> to <ssid_1>; Fake Homeland and fake-laptops stay; the nas is a word.",
                redactor.filter("u1", "Moved fake-laptop to Fake Home; Fake Homeland and fake-laptops stay; the nas is a word.",
                        Map.of(), tally));
        assertEquals(2, tally.identifiersReplaced());
        // In the same text as the name that marks it, too.
        assertEquals("ssid=<ssid_2>\n<ssid_2> is the guest network",
                redactor.filter("u1", "ssid=Fake Guest\nFake Guest is the guest network", Map.of(), new Redactor.Tally()));
        assertEquals("Fake Home", redactor.filter("u2", "Fake Home", Map.of(), new Redactor.Tally()),
                "another user's table knows nothing of it");
    }

    @Test
    @DisplayName("counting what the filter would take adds nothing to the table")
    void countingAddsNothing(@TempDir Path tmp) throws Exception {
        var db = MigratedDatabase.at(tmp.resolve("t.db"));
        var redactor = new Redactor(db);
        var tally = redactor.count("u1", "ssid=Fake Home\nmail alice@example.org, Fake Home", Map.of());
        assertEquals(3, tally.identifiersReplaced());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM privacy_placeholders", Integer.class));
        assertEquals("<email_1>", redactor.filter("u1", "alice@example.org", Map.of(), new Redactor.Tally()));
    }

    @Test
    @DisplayName("a tool call's arguments are restored at every depth; one that names nothing comes back as it was")
    void restoreTree() {
        var redactor = new Redactor(null);
        redactor.filter("u1", "ssid=Fake Home\nstation 00:00:5e:00:53:01", Map.of(), new Redactor.Tally());
        Map<String, Object> args = Map.of("ssid", "<ssid_1>", "macs", List.of("<mac_1>", "x"),
                "nested", Map.of("note", "on <ssid_1>"), "n", 3);
        assertEquals(Map.of("ssid", "Fake Home", "macs", List.of("00:00:5e:00:53:01", "x"),
                "nested", Map.of("note", "on Fake Home"), "n", 3), redactor.restoreTree("u1", args));
        assertEquals(Map.of("vlan_by_mac", Map.of("00:00:5e:00:53:01", 20)),
                redactor.restoreTree("u1", Map.of("vlan_by_mac", Map.of("<mac_1>", 20))), "keys too");
        Map<String, Object> plain = Map.of("q", "no placeholder", "list", List.of("a"));
        assertSame(plain, redactor.restoreTree("u1", plain));
    }

    @Test
    @DisplayName("each detector reads the text once: large and adversarial texts take time in proportion")
    void linearTime() {
        var redactor = new Redactor(null);
        for (String unit : List.of("a@", "a:", "a=", "\"a\":", "1 ", "+1", "$1$", "eyJ", "DE89", "aa:bb:",
                "1.1.1.", "-----BEGIN ", "'", "::", "f:", "WIFI:S:", "a://b:", "--password ", "DHCPA(",
                "a%40", "x (1.2.3.4) at ", "password=x ", "<a_1>", "bot1234567:")) {
            String text = unit.repeat(1_000_000 / unit.length());
            assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> redactor.filter("u1", text, Map.of(), new Redactor.Tally()),
                    "quadratic on a run of " + unit);
        }
    }
}
