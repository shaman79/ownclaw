package com.ownclaw.llm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A single message in an LLM conversation.
 *
 * @param authored written by this application's code, holding nothing a user, a tool or a model
 *                 supplied: the think call's system prompt. The cloud gateway removes the vault's
 *                 values from it and nothing else (CloudGateway). The identifiers the privacy
 *                 filter learns are the user's, and a word of the code's own text that was once
 *                 learned as one -- "unavailable", taken for an SSID from a sensor's state -- was
 *                 rewritten in every request from then on, the system prompt too, so every
 *                 cached prompt had to be written again.
 * @param marked   a think call's first message as the engine wrote it for Anthropic: every marker
 *                 in it ({@link #MARKERS}, {@link #image}) is the engine's, and the engine took the
 *                 markers' control character out of every text it put there ({@link #unmarked}).
 *                 Only such a message is split at its markers, by the provider, into the blocks it
 *                 caches, and sent with none of them; anywhere else marker-like bytes are text a
 *                 user, a tool or a page wrote. The gateway filters it as one text like any other:
 *                 a marker changes nothing the filter removes.
 * @param images   the images a marked message shows, each where its image marker stands
 *                 ({@link #image}): by index into this list. Empty for every other message. Only
 *                 the gateway attaches an image's bytes ({@link Image#sent}), after it has checked
 *                 the file itself; a provider sends an image it has bytes for and no other.
 */
public record LlmMessage(Role role, String content, boolean authored, boolean marked, List<Image> images) {

    public LlmMessage {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** A message with no image. */
    public LlmMessage(Role role, String content, boolean authored, boolean marked) {
        this(role, content, authored, marked, List.of());
    }

    /** A message of data, filtered whole on its way to a cloud model. */
    public LlmMessage(Role role, String content) {
        this(role, content, false, false);
    }

    /** A message of data, or the code's own text ({@link #authored}). */
    public LlmMessage(Role role, String content, boolean authored) {
        this(role, content, authored, false);
    }

    /**
     * Where a prompt's stable prefix ends: in the first message of a think call, after the task,
     * on every step. Anthropic's provider splits there and caches everything before it -- the
     * tools, the system prompt and the task -- for an hour, so the task's later steps read it from
     * the cache while it is unchanged. A line of its own and control characters, like the chat's
     * markers, so no text in the message can be taken for it. Never sent.
     */
    public static final String CACHE_BOUNDARY = "\n\u0001\u001c\n";

    /**
     * Between two messages of the chat a task comes from, in the first message of a think call
     * for Anthropic, and only there: the provider sends each message of the chat as a block of its
     * own. A block that is the same bytes on the chat's next turn is where the next turn's request
     * finds what this one cached; the chat as one block never is -- it is longer by a turn each
     * time, so every turn wrote the whole chat to the cache again. A line of its own, so that no
     * value the privacy filter reads up to the end of a line runs on into it; control characters,
     * which no text the engine puts in the message keeps ({@link #unmarked}). Never sent.
     */
    public static final String HISTORY_BLOCK = "\n\u0001\u001e\n";

    /**
     * Where the chat ends and the task begins, in the same message, a line of its own like
     * {@link #HISTORY_BLOCK}: the provider caches the chat for an hour up to here, so the chat's
     * next turn reads it back and pays only for what was said since. Never sent.
     */
    public static final String HISTORY_END = "\n\u0001\u001d\n";

    /** Every marker of fixed bytes a {@link #marked} message can hold; an image's is {@link #image}. */
    public static final List<String> MARKERS = List.of(HISTORY_BLOCK, HISTORY_END, CACHE_BOUNDARY);

    /**
     * The largest image the Messages API takes, base64-encoded: 10 MB, read as 10,000,000 bytes --
     * the smaller of the two ways to read it. A copy is made to fit it when the image is pasted.
     */
    public static final long IMAGE_BASE64_LIMIT = 10_000_000L;

    /**
     * The largest request the Messages API takes: 128 MB, read as 128,000,000 bytes. Images may take
     * all of it but 32 MB: text that fits any model's context window is far below that (a 1M-token
     * window is a few MB of text), so a request whose images fit this never outgrows the API for its
     * text. Past it, the oldest chat images are shown by their lines alone.
     */
    public static final long IMAGE_BYTES_PER_REQUEST = 128_000_000L - 32_000_000L;

    /**
     * How many images the Messages API takes in one request: 100 for a model with a 200k-token
     * window, 600 for any other -- and 100 while the window is not known yet, the one count every
     * model takes.
     */
    public static int imagesPerRequest(Integer contextWindow) {
        return contextWindow != null && contextWindow > 200_000 ? 600 : 100;
    }

    /** How an image marker opens ({@link #image}). */
    static final String IMAGE_OPENS = "\n\u0001\u001f";

    /** An image marker as it stands in a marked message: its opening, the image's index, the line's end. */
    private static final Pattern IMAGE_MARKER = Pattern.compile("\n\u0001\u001f([0-9]{1,9})\n");

    /**
     * Where image {@code index} of a marked message goes ({@link #images}): before the text of the
     * chat message or the task it was pasted with, which the provider sends as an image block of
     * its own there -- inside that message's blocks, so it takes no cache mark of its own. A line
     * of its own, of control characters and the index, like the other markers: no value the filter
     * reads to the end of a line runs into it, and the digits touch no digit of the text. Never sent.
     */
    public static String image(int index) {
        if (index < 0) throw new IllegalArgumentException("an image's index: " + index);
        return IMAGE_OPENS + index + "\n";
    }

    /**
     * TOOLS: from here on the model is offered these tools too -- deferred ones
     * ({@link ToolSpec#deferred}), named one per line. Anthropic's provider sends it as a
     * mid-conversation tool addition, which leaves the cached prefix as it was; the others add the
     * tools to the array they send ({@link ToolSpec#offered}) and send no message.
     */
    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOLS;

        public String apiValue() {
            return name().toLowerCase();
        }
    }

    /**
     * An image a {@link #marked} message shows at one of its markers ({@link #image}): the file it
     * is, and the pixel size and the bytes of the copy the cloud model is sent -- as the engine
     * knows them, from the task's record of the file. The engine never holds the bytes: the gateway
     * reads them itself, from the file, after it has checked whose the file is and that it was
     * pasted to be shown ({@code CloudGateway}), and attaches them as {@link #sent}.
     *
     * @param width  the copy's width in pixels
     * @param height the copy's height in pixels
     * @param bytes  the copy's size, in bytes before base64
     * @param sent   what the provider sends for it, or null: then it sends nothing
     */
    public record Image(String fileId, int width, int height, long bytes, Sent sent) {

        /** As the engine places it: no bytes. */
        public Image(String fileId, int width, int height, long bytes) {
            this(fileId, width, height, bytes, null);
        }

        /** What the model reads it as: a visual token for each 28x28 pixel patch, Anthropic's own count. */
        public int tokens() {
            return tokens(width, height);
        }

        /** Its size as a request carries it, base64-encoded. */
        public long base64Bytes() {
            return base64Bytes(bytes);
        }

        public static int tokens(int width, int height) {
            return Math.ceilDiv(Math.max(width, 0), 28) * Math.ceilDiv(Math.max(height, 0), 28);
        }

        public static long base64Bytes(long bytes) {
            return 4 * Math.ceilDiv(Math.max(bytes, 0), 3);
        }
    }

    /**
     * The bytes of an image as the gateway sends them, base64-encoded. Made only in this package --
     * by the gateway, from the copy it read and checked itself -- so neither the engine nor a page
     * nor a model can hand a provider bytes of its own.
     */
    public static final class Sent {
        private final String mediaType;
        private final String base64;
        private final long bytes;
        private final String sha256_16;

        Sent(String mediaType, byte[] data, String sha256_16) {
            this.mediaType = mediaType;
            this.base64 = java.util.Base64.getEncoder().encodeToString(data);
            this.bytes = data.length;
            this.sha256_16 = sha256_16;
        }

        public String mediaType() { return mediaType; }
        /** Base64 with no line breaks, as the Messages API takes it. */
        public String base64() { return base64; }
        public long bytes() { return bytes; }
        public String sha256_16() { return sha256_16; }

        @Override
        public String toString() {
            return "Sent[" + mediaType + ", " + bytes + " bytes, sha256 " + sha256_16 + "]";
        }
    }

    /** The message with these images: the gateway's, with the bytes it attached. */
    LlmMessage withImages(List<Image> images) {
        return new LlmMessage(role, content, authored, marked, images);
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(Role.SYSTEM, content);
    }

    /** A system prompt that is the code's own text: see {@link #authored}. */
    public static LlmMessage authoredSystem(String content) {
        return new LlmMessage(Role.SYSTEM, content, true, false);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content);
    }

    /** A think call's first message as the engine wrote it, its markers its own: see {@link #marked}. */
    public static LlmMessage marked(String content) {
        return new LlmMessage(Role.USER, content, false, true);
    }

    /** {@link #marked(String)}, showing these images where its image markers stand. */
    public static LlmMessage marked(String content, List<Image> images) {
        return new LlmMessage(Role.USER, content, false, true, images);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(Role.ASSISTANT, content);
    }

    public static LlmMessage toolsAdded(List<String> names) {
        return new LlmMessage(Role.TOOLS, String.join("\n", names));
    }

    /** The tools a {@link Role#TOOLS} message offers; none for any other. */
    public List<String> addedTools() {
        if (role != Role.TOOLS || content == null || content.isBlank()) return List.of();
        return List.of(content.split("\n"));
    }

    /**
     * {@code text} that can hold no marker: the control character every one of them holds is taken
     * out wherever it stands. What a user, a tool or a page wrote goes into a {@link #marked}
     * message only through here.
     */
    public static String unmarked(String text) {
        return text == null || text.indexOf('\u0001') < 0 ? text : text.replace("\u0001", "");
    }

    /** {@code content} as a model reads it once the provider has split it: no marker in it, an image's neither. */
    public static String asRead(String content) {
        if (content == null) return null;
        String out = content;
        for (String marker : MARKERS) out = out.replace(marker, "");
        return out.contains(IMAGE_OPENS) ? IMAGE_MARKER.matcher(out).replaceAll("") : out;
    }

    /**
     * A piece of a marked message as it is sent: no marker in it, and no marker's control character
     * either. What the filter removed can have taken some of the markers with it -- the chat's end,
     * say, and not the boundary -- and the text left between those still standing holds the rest;
     * the engine put the character in nothing else ({@link #unmarked}), so nothing else of the text
     * goes with it.
     */
    static String plain(String piece) {
        return unmarked(asRead(piece));
    }

    // ── A marked message, as it is sent: one reading, for the provider and the gateway alike ──

    /** A run of a marked message's block: text, or the image at an index of the message's {@link #images}. */
    public sealed interface Piece permits Text, Picture {}

    /** Text as the model reads it: no marker in it. */
    public record Text(String text) implements Piece {}

    /** Where the message's image of this index stands. */
    public record Picture(int index) implements Piece {}

    /**
     * A marked message as the provider sends it -- or null when it holds no boundary, and is sent
     * as the one text the model reads, with no image ({@link #plain}): the chat's messages, each a
     * block of its own, the task, and what follows the boundary. One reading of the markers, which
     * the provider builds its blocks from and the gateway decides by which images are sent: read
     * twice, the two could differ about which image a request carries.
     *
     * @param chat each message of the chat, its pieces in order; empty when the message has no chat
     * @param task the task's pieces
     * @param rest what follows the boundary, as the model reads it
     */
    public record Layout(List<List<Piece>> chat, List<Piece> task, String rest) {

        /** Every image the message places, by index, in the order it stands; each once. */
        public Set<Integer> pictures() {
            var out = new LinkedHashSet<Integer>();
            for (var message : chat) collect(message, out);
            collect(task, out);
            return out;
        }

        private static void collect(List<Piece> pieces, Set<Integer> out) {
            for (Piece p : pieces) if (p instanceof Picture picture) out.add(picture.index());
        }
    }

    /** {@code content}'s layout ({@link Layout}), or null when it holds no boundary after its start. */
    public static Layout layout(String content) {
        int cut = content == null ? -1 : content.lastIndexOf(CACHE_BOUNDARY);
        if (cut <= 0) return null;
        String task = content.substring(0, cut);
        var chat = new ArrayList<List<Piece>>();
        // The engine marks the chat and takes every marker out of the text it puts there, so the
        // first end marker is its own.
        int chatEnd = task.indexOf(HISTORY_END);
        if (chatEnd >= 0) {
            for (String message : task.substring(0, chatEnd).split(Pattern.quote(HISTORY_BLOCK), -1)) {
                chat.add(pieces(message));
            }
            task = task.substring(chatEnd + HISTORY_END.length());
        }
        return new Layout(chat, pieces(task), plain(content.substring(cut + CACHE_BOUNDARY.length())));
    }

    /**
     * A block's text split where its images stand: text pieces as the model reads them, blank ones
     * left out -- the API refuses a block of nothing but blanks -- and a picture at each image
     * marker. A marker the filter cut into is no marker: what is left of it goes as text, without
     * its control character.
     */
    static List<Piece> pieces(String block) {
        var out = new ArrayList<Piece>();
        Matcher m = IMAGE_MARKER.matcher(block);
        int from = 0;
        while (m.find()) {
            text(out, block.substring(from, m.start()));
            out.add(new Picture(Integer.parseInt(m.group(1))));
            from = m.end();
        }
        text(out, block.substring(from));
        return out;
    }

    private static void text(List<Piece> out, String piece) {
        String text = plain(piece);
        if (!text.isBlank()) out.add(new Text(text));
    }

    /**
     * A marked message's content for a provider that is sent no image: its markers gone, and in
     * each image's place a line that says one is there and why it is not shown -- {@code why}, "the
     * local model cannot see images". The engine writes no marked message for such a provider; this
     * is so one sent there all the same shows no marker, and claims no picture it does not have.
     */
    public static String textOnly(String content, String why) {
        if (content == null) return null;
        String out = content;
        if (out.contains(IMAGE_OPENS)) {
            out = IMAGE_MARKER.matcher(out).replaceAll(Matcher.quoteReplacement(
                    "\n[An image was pasted here. It is not shown to you: " + why + ".]\n"));
        }
        return plain(out);
    }

    /**
     * A message as a page shows it -- the debug view, the activity panel: its text as the model
     * reads it, each image a line in its place that says which copy it is, and whether it went.
     */
    public static String asShown(LlmMessage m) {
        String content = m.content();
        if (content == null || !content.contains(IMAGE_OPENS)) return asRead(content);
        Matcher matcher = IMAGE_MARKER.matcher(content);
        var sb = new StringBuilder();
        while (matcher.find()) {
            int i = Integer.parseInt(matcher.group(1));
            Image image = i < m.images().size() ? m.images().get(i) : null;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(image == null ? "\n[image " + i + ": none]\n"
                    : "\n[image " + i + ": the pasted image " + image.fileId() + ", " + image.width() + "x"
                            + image.height() + " px, " + image.bytes() + " bytes"
                            + (image.sent() == null ? "" : ", sent as " + image.sent().mediaType()) + "]\n"));
        }
        matcher.appendTail(sb);
        return asRead(sb.toString());
    }
}
