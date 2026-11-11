package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pasting an image into the chat box attaches it, as a file picked with 📎 is. There is no JS
 * engine in the suite, so the lines that decide are pinned as text, whitespace collapsed; the
 * behaviour was checked in a real browser when it was written.
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

    /** The paste handler, from its listener to the end of its body. */
    private static String handler() {
        String h = page.substring(page.indexOf("inputEl.addEventListener('paste', function(e) {"));
        return h.substring(0, h.indexOf("});") + 3);
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
        assertTrue(h.contains("e.preventDefault(); uploadFiles(files);"), h);
        // Mutation: cancel before looking -> every text paste into the box would do nothing.
        assertTrue(h.indexOf("if (!files.length) return;") < h.indexOf("e.preventDefault();"), h);
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
        assertTrue(f.contains("return new File([f], name, { type: f.type });"), f);
    }

    @Test
    @DisplayName("a chip removed while it uploads is not sent with the next message, and its file is deleted")
    void aRemovedChipIsDropped() {
        String u = between("function uploadFiles(files) {", "function createFileChip(");
        assertTrue(u.contains("if (!chips[i].isConnected) { fetch('/api/files/' + encodeURIComponent(uploaded[i].id), "
                + "{ method: 'DELETE',"), u);
        assertTrue(u.indexOf("if (!chips[i].isConnected)") < u.indexOf("pendingFiles.push("),
                "checked before it joins what the next message carries: " + u);
        assertTrue(u.contains("if (!files[i].size) { addMsg('system', files[i].name + ' is empty, so it was not attached.'); continue; }"),
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
    @DisplayName("the hint under the box says files can be pasted")
    void theHintSaysSo() {
        assertTrue(page.contains("Drag &amp; drop, paste or 📎 to attach files — they stay private"));
    }
}
