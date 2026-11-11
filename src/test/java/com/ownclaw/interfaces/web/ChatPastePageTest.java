package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pasting an image into the chat box attaches it, as a file picked with 📎 is -- and an image the
 * clipboard holds as image data, which the page names itself, is uploaded as a pasted image, which
 * the server shows the cloud model when its bytes decode as one; its chip says who sees it. There
 * is no JS engine in the suite, so the lines that decide are pinned as text, whitespace collapsed;
 * the behaviour was checked in a real browser when it was written.
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
        return between("inputEl.addEventListener('paste', function(e) {", "var clipboardImages = new WeakSet();");
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
        assertTrue(h.contains("e.preventDefault(); var images = files.filter(function(f) { return clipboardImages.has(f); }); "
                + "var others = files.filter(function(f) { return !clipboardImages.has(f); }); "
                + "if (images.length) uploadFiles(images, true); if (others.length) uploadFiles(others, false);"), h);
        // Mutation: cancel before looking -> every text paste into the box would do nothing.
        assertTrue(h.indexOf("if (!files.length) return;") < h.indexOf("e.preventDefault();"), h);
    }

    @Test
    @DisplayName("only image data the page named itself goes up as a pasted image: a file copied in a file manager is a file")
    void onlyClipboardImageDataIsPasted() {
        String f = between("function pastedFiles(data) {", "function uploadFiles(");
        assertTrue(f.contains("if (f) { found.push(f); onDisk.push(!!entry); }"), "a file system entry is remembered: " + f);
        assertTrue(f.contains("var image = /^image\\//.test(f.type) && !onDisk[k];"), f);
        assertTrue(f.contains("if (image) clipboardImages.add(named); return named;"), f);
        // A file that keeps its own name -- a file manager's -- is returned before it can be marked.
        assertTrue(f.indexOf("if (f.name && !/^image\\.\\w+$/i.test(f.name)) return f;") < f.indexOf("clipboardImages.add("), f);
        assertTrue(page.contains("var clipboardImages = new WeakSet();"));
        String u = between("function uploadFiles(files, pasted) {", "function createFileChip(");
        assertTrue(u.contains("if (pasted) formData.append('pasted', 'true');"), "the claim, for the server to check: " + u);
        assertTrue(u.indexOf("if (!chips[i].isConnected)") < u.indexOf("if (pasted) markSeen(chips[i], uploaded[i]);"), u);
        // Mutation: mark every pasted file -> a statement copied in a file manager goes up as an image.
    }

    @Test
    @DisplayName("a pasted image's chip says who sees it, as text: the cloud model, nobody on Free, or nobody because it stays private")
    void theChipSaysWhoSeesIt() {
        String mark = between("function markSeen(chip, uploaded) {", "function chatRunsFree()");
        assertTrue(mark.contains("chip.dataset.shown = uploaded.shown_to_cloud ? '1' : '';"), "the server's word, not the page's: " + mark);
        assertTrue(mark.contains("var seen = node('span', 'file-chip-seen');"), mark);
        String free = between("function chatRunsFree() {", "function seenLine(");
        assertTrue(free.contains("return (costSel.value || defaultCostMode) === 'free';"), free);
        String line = between("function seenLine(chip) {", "function refreshSeen()");
        assertTrue(line.contains("if (chip.dataset.shown !== '1') { seen.textContent = '🔒 private';"), line);
        assertTrue(line.contains("} else if (chatRunsFree()) { seen.textContent = '🏠 Free: the local model can\\'t see images';"), line);
        assertTrue(line.contains("the local model cannot see images"), line);
        assertTrue(line.contains("} else { seen.textContent = '☁️ the cloud model will see it';"), line);
        assertTrue(line.contains("the privacy filter reads text, not pictures"), line);
        assertFalse(line.contains("innerHTML") || mark.contains("innerHTML"), "text, never markup");
        assertTrue(page.contains("costSel.addEventListener('change', refreshSeen);"), "the chat's choice changes the line");
        assertTrue(between("function nameDefaults(defaults) {", "var defaultCostMode;").contains("defaultCostMode = defaults.costMode; refreshSeen();"));
        // Mutation: say "sees it" before checking Free -> a Free chat's chip claims the cloud sees the image.
    }

    @Test
    @DisplayName("a paste that carries text pastes the text: a copy from a spreadsheet brings a picture of the cells too")
    void textWins() {
        String h = handler();
        assertTrue(h.contains("if ((e.clipboardData.getData('text/plain') || '').trim()) return;"), h);
        assertTrue(h.indexOf("getData('text/plain')") < h.indexOf("e.preventDefault();"),
                "the text is looked at before the paste is cancelled: " + h);
    }

    @Test
    @DisplayName("every typed file is taken, a folder's empty type is not; only the browser's own 'image.png' is renamed, distinct within a second")
    void pastedFilesAreNamed() {
        String f = between("function pastedFiles(data) {", "function uploadFiles(");
        assertTrue(f.contains("var entry = items[i].webkitGetAsEntry ? items[i].webkitGetAsEntry() : null; "
                + "if (entry ? entry.isDirectory : !items[i].type) continue;"), "a folder out, a README in: " + f);
        assertTrue(f.contains("if (f.name && !/^image\\.\\w+$/i.test(f.name)) return f;"),
                "a file copied in a file manager keeps its name: " + f);
        assertTrue(f.contains("if (stamp !== pasteSecond) { pasteSecond = stamp; pastedInSecond = 0; }"), f);
        assertTrue(f.contains("+ (pastedInSecond > 1 ? '-' + pastedInSecond : '')"), f);
        assertTrue(f.contains("named = new File([f], name, { type: f.type });"), f);
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
        assertTrue(u.contains("addMsg('system', 'Upload failed: ' + mdLiteral(data.error));"), u);
        assertTrue(u.contains("addMsg('system', 'Upload failed: ' + mdLiteral(err.message));"), u);
        assertTrue(page.contains("return String(text).replace(/[\\\\`*_{}\\[\\]()#+\\-.!<>|~&]/g, '\\\\$&');"),
                "every character markdown or HTML could read, escaped");
    }

    @Test
    @DisplayName("the hint under the box says it plainly: files stay private, pasted images are shown to the cloud model, unfiltered")
    void theHintSaysSo() {
        assertTrue(page.contains("Files you attach with 📎 or drag &amp; drop stay private &nbsp;&middot;&nbsp; Images you "
                + "paste are shown to the cloud model as they are — the privacy filter reads text, not pictures"), page.substring(
                page.indexOf("<div class=\"input-hint\">"), page.indexOf("<div class=\"input-hint\">") + 400));
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
        assertTrue(panel.contains("if (totals.images) {"), panel);
        assertTrue(panel.contains("the privacy filter reads text, not pictures"), panel);
        assertTrue(panel.contains("if (a.kind === 'pasted image') {"), "a pasted image is said as one: " + panel);
        assertFalse((call + panel).contains("innerHTML"), "text, never markup");
    }
}
