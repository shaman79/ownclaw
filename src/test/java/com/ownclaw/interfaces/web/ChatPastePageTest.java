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

    @Test
    @DisplayName("an image pasted with no text beside it is uploaded as an attachment, the paste itself cancelled")
    void anImageIsAttached() {
        String h = handler();
        assertTrue(h.contains("var images = pastedImages(e.clipboardData); if (!images.length) return;"), h);
        assertTrue(h.contains("e.preventDefault(); uploadFiles(images);"), h);
        // Mutation: cancel before looking -> every text paste into the box would do nothing.
        assertTrue(h.indexOf("if (!images.length) return;") < h.indexOf("e.preventDefault();"), h);
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
    @DisplayName("each pasted image is named for when it was pasted, with its type's extension")
    void pastedImagesAreNamed() {
        String f = page.substring(page.indexOf("function pastedImages(data) {"));
        f = f.substring(0, f.indexOf("function createFileChip("));
        assertTrue(f.contains("items[i].kind === 'file' && /^image\\//.test(items[i].type)"), f);
        assertTrue(f.contains("var name = 'pasted-image-' + stamp + (found.length > 1 ? '-' + (k + 1) : '') + '.' + ext;"), f);
        assertTrue(f.contains("return new File([f], name, { type: f.type });"), f);
    }

    @Test
    @DisplayName("an image's chip shows it as a data: URL, which the page's policy allows; a blob: URL would show nothing")
    void theThumbnailIsADataUrl() {
        assertTrue(page.contains("var chip = createFileChip(files[i].name, null, files[i]);"));
        String chip = page.substring(page.indexOf("function createFileChip(name, fileId, file) {"));
        chip = chip.substring(0, chip.indexOf("fileChips.appendChild(chip);"));
        assertTrue(chip.contains("reader.readAsDataURL(file);"), chip);
        assertFalse(page.contains("URL.createObjectURL("),
                "img-src is 'self' data: (SecurityHeadersFilter): a blob: thumbnail is blocked");
    }

    @Test
    @DisplayName("the hint under the box says an image can be pasted")
    void theHintSaysSo() {
        assertTrue(page.contains("Drag &amp; drop, paste an image or 📎 to attach files — they stay private"));
    }
}
