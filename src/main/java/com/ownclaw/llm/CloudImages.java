package com.ownclaw.llm;

/**
 * Where the gateway reads an image it may send to the cloud model: the copy made of it when it was
 * pasted ({@code FileStorageService}, {@code CloudImageCopy}).
 * <p>
 * The engine names an image by its file only ({@link LlmMessage.Image}); what decides that its
 * bytes may leave is this, asked by the gateway for every image of every request: the file is the
 * task's user's, it was marked shown to the cloud when it was uploaded -- as an image the page named
 * itself on a paste, whose bytes decoded as one -- and the copy is the bytes recorded then. A
 * private file was never marked, so no model output, no page and no id the browser sends can make
 * one go.
 */
@FunctionalInterface
public interface CloudImages {

    /** The copy of {@code fileId} the cloud model may be sent on {@code userId}'s behalf, or why there is none. */
    Found copyOf(String userId, String fileId);

    /**
     * The copy, as it was recorded at upload and read now.
     *
     * @param mediaType image/png, image/jpeg or image/gif
     * @param sha256    the hex SHA-256 of {@code bytes}, as recorded at upload
     */
    record Copy(String mediaType, int width, int height, byte[] bytes, String sha256) {}

    /** A copy to send, or why the image is withheld. Exactly one of the two. */
    record Found(Copy copy, String withheld) {
        public static Found of(Copy copy) {
            return new Found(copy, null);
        }

        public static Found withheld(String why) {
            return new Found(null, why);
        }
    }

    /** No image is ever found: a gateway wired with this sends none. */
    CloudImages NONE = (userId, fileId) -> Found.withheld("no image store is wired to the gateway");
}
