package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pasting or dropping files on the chat box attaches them, as files picked with 📎 are -- and an
 * image among them, whatever it came from (a screenshot, a copied picture, a photo copied in a file
 * manager), is uploaded as a picture to show, which the server shows the cloud model when its
 * bytes decode as one; its chip says who sees it. Every other file stays private, and so does
 * anything picked with 📎. There is no JS engine in the suite, so the lines that decide are pinned
 * as text, whitespace collapsed.
 * <p>
 * Pinned text says the lines are there, not that a browser reads them as meant. Who sees an image
 * once rested on whether a file system held it, which each browser answers its own way: checked in
 * Chromium alone, the rule took every screenshot pasted in Firefox for a file manager's file and
 * kept it private (2026-11-25). The owner then asked for every image he pastes to be seen
 * (2026-11-26), so that rests on the file's type alone now. A change to pastedFiles is still run
 * in Chromium and in Firefox, with a real clipboard paste in each, before it is merged.
 */
class ChatPastePageTest {

    static String page;

    @BeforeAll
    static void read() throws Exception {
        try (var in = ChatPastePageTest.class.getResourceAsStream("/static/index.html")) {
            assertNotNull(in, "static/index.html is not on the classpath");
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
    }

    /** The paste handler, from its listener to the end of its body: up to the set it reads. */
    private static String handler() {
        return between("inputEl.addEventListener('paste', function(e) {", "function isImage(f) {");
    }

    private static String between(String from, String to) {
        String s = page.substring(page.indexOf(from));
        return s.substring(0, s.indexOf(to));
    }

    @Test
    @DisplayName("files pasted with no text beside them are uploaded as attachments, the paste itself cancelled")
    void filesAreAttached() {
        String h = handler();
        assertTrue(h.contains("var files = pastedFiles(e.clipboardData); if (!files.length) return;"), h);
        assertTrue(h.contains("e.preventDefault(); uploadGiven(files); });"), h);
        // Mutation: cancel before looking -> every text paste into the box would do nothing.
        assertTrue(h.indexOf("if (!files.length) return;") < h.indexOf("e.preventDefault();"), h);
    }

    @Test
    @DisplayName("every image pasted or dropped goes up as a picture to show, by its type alone; every other file, and all picked with 📎, as a file")
    void imagesAreShownWhereverTheyCameFrom() {
        String given = between("function uploadGiven(files) {", "var PASTED_NAME");
        assertTrue(given.contains("var images = files.filter(isImage).map(function(f) { return PASTED_NAME.test(f.name || '') ? f : renamed(f); }); "
                + "var others = files.filter(function(f) { return !isImage(f); }); "
                + "if (images.length) uploadFiles(images, true); if (others.length) uploadFiles(others, false); }"), given);
        String is = between("function isImage(f) {", "function uploadGiven(files) {");
        assertEquals("function isImage(f) { return /^image\\//.test(f.type || ''); } /** * Files pasted or dropped on the box: ",
                is.substring(0, is.indexOf("the images among")), "its whole body");
        // Dropped files are given as pasted ones are -- but on 📎 itself, as 📎 takes them: never with the claim.
        String drop = between("inputWrap.addEventListener('drop', function(e) {", "inputEl.addEventListener('paste'");
        assertTrue(drop.contains("if (!e.dataTransfer.files.length) return; if (attachBtn.contains(e.target)) uploadFiles(e.dataTransfer.files); "
                + "else uploadGiven(Array.prototype.slice.call(e.dataTransfer.files)); });"), drop);
        assertTrue(page.contains("fileInput.addEventListener('change', function() { if (fileInput.files.length) uploadFiles(fileInput.files);"),
                "📎 is the private way to give a picture: no claim");
        // Every call of uploadFiles, the claim made in one alone: 📎, a drop on 📎, uploadGiven's two.
        assertEquals(5, page.split("uploadFiles\\(", -1).length - 1, "four calls and the definition");
        assertEquals(1, page.split("uploadFiles\\(images, true\\)", -1).length - 1, "one place makes the claim");
        assertEquals(1, page.split("uploadFiles\\(others, false\\)", -1).length - 1, "and one says it is no claim");
        assertEquals(3, page.split("uploadGiven\\(", -1).length - 1, "its definition, the drop and the paste");
        assertEquals(1, page.split("addEventListener\\('paste'", -1).length - 1, "one paste listener");
        assertEquals(1, page.split("addEventListener\\('drop'", -1).length - 1, "one drop listener");
        // The claim made nowhere else: one FormData, one 'pasted' field, and that in uploadFiles.
        assertEquals(1, page.split("new FormData\\(", -1).length - 1, "one upload body");
        assertEquals(1, page.split("append\\('pasted'", -1).length - 1, "one claim");
        assertEquals(1, page.split("pasted=true", -1).length - 1 + page.split("'pasted', 'true'", -1).length - 1, "said once");
        String f = between("function pastedFiles(data) {", "function uploadFiles(");
        for (String gone : new String[]{"clipboardImages", "onDisk", "madeNow", "entryForAnyFile", "holdsDiskFile"}) {
            assertFalse(page.contains(gone), "where a file came from decides nothing now: " + gone);
        }
        // A clipboard that lists its files but not its items: each typed one, an image shown as any is.
        assertTrue(f.contains("if (data.files[j].type) found.push(data.files[j]);"), f);
        String u = between("function uploadFiles(files, pasted) {", "function createFileChip(");
        assertTrue(u.contains("if (pasted) formData.append('pasted', 'true');"), "the claim, for the server to check: " + u);
        assertTrue(u.indexOf("if (!chips[i].isConnected)") < u.indexOf("markSeen(chips[i], uploaded[i], pasted);"), u);
        // Mutation: uploadGiven(files) -> uploadFiles(files, true) -> a pasted PDF goes up with the
        // claim (the server keeps it private, but the chip's line is the image's); the drop back to
        // uploadFiles(e.dataTransfer.files) -> a dropped photo is private; 📎 through uploadGiven ->
        // there is no private way to give a picture; drop the 📎 test in the drop -> a photo dropped
        // on 📎 is shown (review, 2026-11-26).
    }

    @Test
    @DisplayName("only what a file is called rests on when the paste made it: the browser's own name, in any language, is replaced; every shown image is named by the page")
    void theDateNamesNoMore() {
        String given = between("function uploadGiven(files) {", "var PASTED_NAME");
        assertTrue(given.contains("PASTED_NAME.test(f.name || '') ? f : renamed(f)"), "a shown image's own name could carry an account number");
        assertTrue(page.contains("var PASTED_NAME = /^pasted-image-\\d{6}(?:-\\d{1,4})?(?:\\.[A-Za-z][A-Za-z0-9]{0,4})?$/;"));
        String renamed = between("function renamed(f) {", "/** The second the last pasted file");
        assertTrue(renamed.contains("try { return new File([f], pastedName(f), { type: f.type }); } catch (err) { return f;"), renamed);
        String name = between("function pastedName(f) {", "/** * Whether the paste itself made");
        assertTrue(name.contains("var own = /\\.([A-Za-z][A-Za-z0-9]{0,4})$/.exec(f.name || '');"), name);
        assertTrue(name.contains("if (!/^[a-z][a-z0-9]{0,4}$/i.test(ext)) ext = '';"), name);
        String made = between("function madeByThePaste(f) {", "function pastedFiles(data) {");
        assertTrue(made.contains("return Math.abs(Date.now() - f.lastModified) < 1000; }"), made);
        String f = between("function pastedFiles(data) {", "function uploadFiles(");
        assertEquals(1, f.split("madeByThePaste\\(", -1).length - 1, f);
        assertTrue(f.contains("if (f.name && !/^image\\.\\w+$/i.test(f.name) && !(isImage(f) && madeByThePaste(f))) return f;"), f);
        // Mutation: drop madeByThePaste from the name -> two screenshots pasted in a Czech Firefox are
        // both "obrazek.png"; drop isImage -> a text file written this second loses its own name.
    }

    @Test
    @DisplayName("every chip says who sees its file, as text, with what that means a tap away: private, readable, the cloud model, nobody on Free, nobody with a cloud sent no images")
    void theChipSaysWhoSeesIt() {
        String mark = between("function markSeen(chip, uploaded, pasted) {", "function seenFrom(chip, said) {");
        assertTrue(mark.contains("chip.classList.add(pasted ? 'pasted' : 'attached');"), "every chip, a file's too: " + mark);
        assertTrue(mark.contains("seenFrom(chip, pasted ? uploaded : {});"), "a 📎 file is private whatever the answer says: " + mark);
        assertTrue(mark.contains("var seen = node('button', 'file-chip-seen');"), "a button, which a touch screen can tap: " + mark);
        assertTrue(mark.contains("var open = chip.classList.toggle('open'); seen.setAttribute('aria-expanded', open ? 'true' : 'false');"), mark);
        String from = between("function seenFrom(chip, said) {", "function switchCloud(chip) {");
        assertTrue(from.contains("chip.dataset.shown = said.shown_to_cloud ? '1' : '';"), "the server's word: " + from);
        assertTrue(from.contains("chip.dataset.cloudVisible = said.cloud_visible ? '1' : '';"), from);
        assertTrue(from.contains("chip.dataset.carries = said.metadata ? String(said.metadata) : '';"), from);
        String free = between("function chatRunsFree() {", "var cloudSeesImages");
        assertTrue(free.contains("return (costSel.value || defaultCostMode) === 'free';"), free);
        String line = between("function seenLine(chip) {", "function refreshSeen()");
        int readable = line.indexOf("if (chip.dataset.cloudVisible === '1') { line = chatRunsFree() ? '🏠 Free: no cloud model reads it' : '☁️ the cloud model can read it';");
        int kept = line.indexOf("} else if (chip.dataset.shown !== '1') { line = '🔒 private';");
        int onFree = line.indexOf("} else if (chatRunsFree()) { line = '🏠 Free: the local model can\\'t see it';");
        int notSent = line.indexOf("} else if (!cloudSeesImages) { line = '☁️ not seen: the cloud model is sent no images';");
        int sees = line.indexOf("} else { line = '☁️ the cloud model will see it';");
        assertTrue(readable >= 0 && kept > readable && onFree > kept && notSent > onFree && sees > notSent,
                "each case, in this order -- Free and a cloud sent none before any 'will see it': " + line);
        assertTrue(line.contains("+ (cloudSeesImages ? 'If the chat later runs on the cloud model, the image goes with "
                + "the chat\\'s history, as its ' + 'text does. ' : 'Nor would the cloud model be, if the chat later ran on "
                + "it: it is sent no images. ')"), "Free says what a later cloud turn does -- and with a cloud sent none, not that: " + line);
        assertFalse(line.contains("cloudProvider"), "the provider is the owner's setting, not every account's: " + line);
        assertTrue(line.contains("+ (chip.dataset.carries ? 'Its file data — ' + chip.dataset.carries + ': a time, a place, a camera, ' "
                + "+ 'as the file holds them — is given to it too, as text, through the privacy filter. ' : '')"), line);
        assertFalse(line.contains("skill results private"), "one line for every image the cloud model sees: " + line);
        assertTrue(line.contains("seen.textContent = line; seen.title = full; detail.textContent = full;"), line);
        assertFalse(line.contains("innerHTML") || mark.contains("innerHTML"), "text, never markup");
        assertTrue(page.contains("costSel.addEventListener('change', refreshSeen);"), "the chat's choice changes the line");
        assertTrue(page.contains("var chips = fileChips.querySelectorAll('.file-chip.pasted, .file-chip.attached');"));
        // Mutation: say "will see it" before checking Free or the cloud -> a chip claims what does not happen.
    }

    @Test
    @DisplayName("every chip has a switch: the owner lets the cloud see the file, or keeps it private, before he sends it; the server makes it")
    void theSwitch() {
        String mark = between("function markSeen(chip, uploaded, pasted) {", "function seenFrom(chip, said) {");
        assertTrue(mark.contains("var sw = node('button', 'file-chip-switch'); sw.type = 'button'; "
                + "sw.addEventListener('click', function() { switchCloud(chip); });"), mark);
        String sw = between("function switchCloud(chip) {", "function chatRunsFree() {");
        assertTrue(sw.contains("if (!chip.dataset.fileId || chip.classList.contains('uploading')) return;"), "one choice at a time, once uploaded: " + sw);
        assertTrue(sw.contains("var visible = !(chip.dataset.shown === '1' || chip.dataset.cloudVisible === '1');"), sw);
        assertTrue(sw.contains("chip.classList.add('uploading'); if (sw) sw.disabled = true;"), "a message waits for the choice (send): " + sw);
        assertTrue(sw.contains("fetch('/api/files/' + encodeURIComponent(chip.dataset.fileId) + '/cloud', { method: 'POST', "
                + "headers: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' }, body: JSON.stringify({ visible: visible }) })"), sw);
        assertTrue(sw.contains("if (!chip.isConnected || authToken !== token) return; seenFrom(chip, said); chip.dataset.chosen = '1'; seenLine(chip);"),
                "the server's word, not the click's: " + sw);
        // No answer, or a refusal: who sees it is not known, so it goes with no message -- taken off, as its ✕ does.
        assertTrue(sw.contains("}, function() { if (!chip.isConnected || authToken !== token) return; "
                + "pendingFiles = pendingFiles.filter(function(f) { return f.chip !== chip; }); chip.remove(); "
                + "addMsg('system', 'Could not switch who sees a file, so it was taken off this message. Attach it again.'); });"), sw);
        assertFalse(sw.contains("undecided"), "no state but private and shown: " + sw);
        String line = between("function seenLine(chip) {", "function refreshSeen()");
        assertTrue(line.contains("sw.textContent = open ? '🔒 keep private' : '☁️ let the cloud see it';"), line);
        assertTrue(page.contains("if (!picked && fileChips.querySelector('.file-chip.uploading')) {"), "Send waits for a switch under way");
        // Mutation: set the line from the click instead of the answer -> a refused switch says the file is shown.
        // Mutation: draw it private on a lost answer -> a restart's 502 on "keep private" sends a shown file (review of 5d134e4).
    }

    @Test
    @DisplayName("opening another chat says, on a chip pasted in the last one, who sees it in this one: after the chat's own choice is set")
    void theChipFollowsTheChatSwitch() {
        String show = between("function showChatOptions(options, defaults, cloud) {", "function nameDefaults(defaults)");
        int set = show.indexOf("costSel.value = options.costMode || '';");
        int refresh = show.lastIndexOf("refreshSeen();");
        assertTrue(set >= 0 && refresh > set, "refreshed after the value is set, which fires no change event: " + show);
        assertTrue(show.contains("noteCloud(cloud);"), "and the chat's cloud: " + show);
        assertTrue(page.contains("showChatOptions(data.options, data.defaults, data.cloud);"));
        String note = between("function noteCloud(cloud) {", "function seenLine(chip)");
        assertTrue(note.contains("cloudSeesImages = cloud.seesImages !== false;"), note);
        assertTrue(page.contains("noteCloud({ seesImages: provider === 'anthropic' });"), "the settings page's provider too");
        assertTrue(note.contains("var hint = document.getElementById('hint-images'); if (hint) hint.textContent = cloudSeesImages "
                + "? 'Images you paste or drop here — screenshots, photos (PNG, JPEG, GIF) — are shown to the cloud model"), "and the hint under the box: " + note);
        assertTrue(page.contains("<span id=\"hint-images\">Images you paste or drop here"), "the hint's words are that span");
        assertTrue(note.contains("'This chat\\'s cloud model is sent no images: images you paste or drop are not shown to it, "
                + "and skills ' + 'are given them as files;"), note);
        // Mutation: refresh in nameDefaults only -> the chip keeps the last chat's wording (the review's Free-to-Cheaper send).
    }

    @Test
    @DisplayName("on a narrow screen a chip is no wider than the row: its name and line shrink to an ellipsis, the remove button never does")
    void narrowScreens() {
        String css = between("/* * Every chip says who sees its file", ".file-chip.uploading {");
        assertTrue(css.contains(".file-chip { max-width: 100%; box-sizing: border-box; min-width: 0 }"), css);
        assertTrue(css.contains("min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"), css);
        assertTrue(css.contains(".file-chip-remove { flex-shrink: 0 }"), css);
        assertTrue(css.contains(".file-chip.open { flex-wrap: wrap; border-radius: 12px }"), css);
        assertFalse(css.contains("max-width: 360px"), "no width of its own past the row's");
        // Mutation: give the line flex-shrink 0 again -> at 320 px the remove button is pushed off the screen.
    }

    @Test
    @DisplayName("every typed file is taken, a folder's empty type is not; only the browser's own 'image.png' is renamed, distinct within a second")
    void pastedFilesAreNamed() {
        String f = between("function pastedFiles(data) {", "function uploadFiles(");
        assertTrue(f.contains("var entry = typeof items[i].webkitGetAsEntry === 'function' ? items[i].webkitGetAsEntry() : null; "
                + "if (entry ? entry.isDirectory : !items[i].type) continue;"), "a folder out, a README in: " + f);
        assertTrue(f.contains("if (f.name && !/^image\\.\\w+$/i.test(f.name) && !(isImage(f) && madeByThePaste(f))) return f;"),
                "a file copied in a file manager keeps its name: " + f);
        assertTrue(f.contains("return renamed(f);"), f);
        String name = between("function pastedName(f) {", "/** * Whether the paste itself made");
        assertTrue(name.contains("if (stamp !== pasteSecond) { pasteSecond = stamp; pastedInSecond = 0; }"), name);
        assertTrue(name.contains("+ (pastedInSecond > 1 ? '-' + pastedInSecond : '')"), name);
    }

    @Test
    @DisplayName("an upload OwnClaw does not answer -- a restart's 502, 503, 504, or no answer at all -- is sent again for a while; a refusal is not")
    void anUploadDuringARestartIsSentAgain() {
        String u = between("function uploadFiles(files, pasted) {", "function createFileChip(");
        assertTrue(u.contains("var token = authToken; postUpload(formData, chips, Date.now(), 1, token) .then(function(data) {"), u);
        assertTrue(u.contains("method: 'DELETE', headers: { 'Authorization': 'Bearer ' + token }"),
                "a file whose chip is gone is deleted as whoever sent it: " + u);
        String post = between("function postUpload(formData, chips, startedAt, attempt, token) {", "function createFileChip(");
        assertTrue(post.contains("if (!chips.some(function(c) { return c.isConnected; })) return { files: [] }; "
                + "if (authToken !== token) throw new Error("), "after the wait the chips first, silently: " + post);
        assertTrue(post.contains("headers: { 'Authorization': 'Bearer ' + token },"), "as whoever made the first try: " + post);
        assertEquals(2, post.split("if \\(authToken !== token\\) throw new Error\\('you signed out while it was being sent'\\);", -1).length - 1,
                "signed out meanwhile, before the wait and after it: " + post);
        assertTrue(page.contains("fileChips.innerHTML = ''; pendingFiles = []; setConnState(false, 'Offline');"),
                "the box's files go with the account");
        assertTrue(page.contains("logoutBtn.addEventListener('click', function() { logout(); });"), "the click is no reason");
        assertTrue(post.contains("if (notAnswering(r.status)) return again('HTTP ' + r.status);"), post);
        assertTrue(post.contains("}, function(err) { return again(err && err.message ? err.message : 'no answer'); });"), post);
        assertTrue(post.contains("if (!chips.some(function(c) { return c.isConnected; })) return Promise.resolve({ files: [] });"),
                "stopped when every chip is removed: " + post);
        assertTrue(post.contains("if (Date.now() - startedAt >= UPLOAD_RETRY_MS) {"), post);
        assertTrue(post.contains("setTimeout(resolve, Math.min(1000 * attempt, 5000))"), post);
        assertTrue(page.contains("function notAnswering(status) { return status === 502 || status === 503 || status === 504; }"));
        assertTrue(page.contains("var UPLOAD_RETRY_MS = 90000;"));
        assertTrue(page.contains("if (!picked && fileChips.querySelector('.file-chip.uploading')) {"),
                "the message waits for chips still uploading, retried ones too");
        // Mutation: retry every status -> a refused file (413, 400) is sent for 90 seconds; drop the
        // chip check -> a removed paste is uploaded anyway; read authToken on each try -> a file
        // retried across a sign-out lands in the next account (review, 2026-11-26).
    }

    @Test
    @DisplayName("a chip removed while it uploads is not sent with the next message, and its file is deleted")
    void aRemovedChipIsDropped() {
        String u = between("function uploadFiles(files, pasted) {", "function createFileChip(");
        assertTrue(u.contains("if (!chips[i].isConnected) { fetch('/api/files/' + encodeURIComponent(uploaded[i].id), "
                + "{ method: 'DELETE',"), u);
        assertTrue(u.indexOf("if (!chips[i].isConnected)") < u.indexOf("pendingFiles.push("),
                "checked before it joins what the next message carries: " + u);
        assertTrue(u.contains("if (!files[i].size) { addMsg('system', mdLiteral(files[i].name) + ' is empty, so it was not attached.'); continue; }"),
                "an empty file would shift the answer against the chips, and is said, not dropped in silence");
    }

    @Test
    @DisplayName("an image's chip shows a small data: picture, which the page's policy allows; one the browser cannot draw shows none")
    void theThumbnailIsSmallAndSafe() {
        assertTrue(page.contains("var chip = createFileChip(files[i].name, null, files[i]);"));
        String chip = between("function createFileChip(name, fileId, file) {", "fileChips.appendChild(chip);");
        assertTrue(chip.contains("thumb.onerror = function() { thumb.remove(); };"), chip);
        assertTrue(chip.contains("chipPicture(file, chip, function(url) { if (url) thumb.src = url; else thumb.remove(); });"), chip);
        String picture = between("function chipPicture(file, chip, done) {", "// Send");
        assertTrue(picture.contains("pictureQueue = pictureQueue.then(function() { if (!chip.isConnected) { done(null); return; }"),
                "one picture at a time, none for a chip already gone: " + picture);
        assertTrue(picture.contains("}).catch(function() {});"), "a failed picture does not stop the ones after it");
        assertTrue(picture.contains("createImageBitmap(file, { resizeHeight: 44, resizeQuality: 'medium' })"), picture);
        assertTrue(picture.contains("done(canvas.toDataURL('image/png'));"), picture);
        assertFalse((chip + picture).contains("createObjectURL"),
                "img-src is 'self' data: (SecurityHeadersFilter): a blob: picture is blocked");
    }

    @Test
    @DisplayName("a file's name in a notice is shown as it is: the notices are rendered as markdown")
    void namesInNoticesAreLiteral() {
        String u = between("function uploadFiles(files, pasted) {", "function createFileChip(");
        assertTrue(u.contains("addMsg('system', mdLiteral(files[i].name) + ' is empty, so it was not attached.');"), u);
        assertTrue(u.contains("if (shown) addMsg('system', 'Upload failed: ' + mdLiteral(data.error));"), u);
        assertTrue(u.contains("if (shown) addMsg('system', 'Upload failed: ' + mdLiteral(err.message));"), u);
        assertTrue(page.contains("return String(text).replace(/[\\\\`*_{}\\[\\]()#+\\-.!<>|~&]/g, '\\\\$&');"),
                "every character markdown or HTML could read, escaped");
    }

    @Test
    @DisplayName("the hint under the box says it plainly: images pasted or dropped are shown to the cloud model, unfiltered; other files and 📎 stay private")
    void theHintSaysSo() {
        assertTrue(page.contains("Images you paste or drop here — screenshots, photos (PNG, JPEG, GIF) — are shown to the cloud model as pictures "
                + "(the privacy filter reads text, not pictures); other files, and anything you attach with 📎, stay private "
                + "— switch any file on its chip before you send"),
                page.substring(page.indexOf("<div class=\"input-hint\">"), page.indexOf("<div class=\"input-hint\">") + 500));
        assertFalse(page.contains("paste or 📎 to attach files — they stay private"), "pasted images are not private");
    }

    @Test
    @DisplayName("the task page's Sent to cloud names every image a request carried and every one kept back, and that the filter does not read them")
    void theTaskPageNamesTheImages() {
        String call = between("function buildTaskCallElement(c) {", "// ── Small pieces of the task detail page");
        assertTrue(call.contains("(c.images || []).forEach(function(im) { d.appendChild(tdRow('Image', "), call);
        assertTrue(call.contains("im.mediaType + ', ' + im.width + '×' + im.height + ' px, ' + formatBytes(im.bytes) + ' · sha256 ' + im.sha256_16"), call);
        assertTrue(call.contains("(c.withheld || []).forEach(function(w) { d.appendChild(tdRow('Image withheld', "), call);
        String panel = between("function buildTaskSentPanel(t, recorded) {", "function buildTaskCallElement(c) {");
        assertTrue(panel.contains("if (totals.images || totals.imagesFailed) {"), panel);
        assertTrue(panel.contains("tdCount(totals.images, 'image', 'images') + ' you pasted went with ' + tdCount(totals.imageRequests, 'request', 'requests')"),
                "each image once, with how many requests: " + panel);
        assertTrue(panel.contains("only in requests that failed with nothing billed, which may not have reached the provider"), panel);
        assertTrue(panel.contains("(totals.imageRequestsFailed ? ' (' + totals.imageRequestsFailed + ' failed after the model had read '"), panel);
        assertTrue(page.contains("+ (a.shownFailed ? ' (' + a.shownFailed + ' failed after the model had read '"), "and each image's line");
        assertTrue(panel.contains("the privacy filter reads text, not pictures"), panel);
        assertTrue(panel.contains("if (a.kind === 'pasted image') {"), "a pasted image is said as one: " + panel);
        assertTrue(panel.contains("a.shownWith ? 'Shown to the cloud model with ' + tdCount(a.shownWith, 'request', 'requests')"), panel);
        assertTrue(panel.contains(": 'Not shown to the cloud model in this task: no request that went carried it. '"),
                "a Free task's image is not said to have been shown: " + panel);
        assertFalse((call + panel).contains("innerHTML"), "text, never markup");
    }
}
