"""
OwnClaw Core Skill: text_summarize
Performs extractive text summarization using sentence scoring.

No external dependencies — uses only Python stdlib.
Scores sentences by word frequency (TF) and position, then selects top sentences.

Parameters:
  text           - the text to summarize (required)
  max_sentences  - maximum number of sentences in summary (default 5)
  max_length     - maximum character length of summary (default 1000)
  style          - "bullets" for bullet-point list, "paragraph" for joined text (default "paragraph")
"""
import json
import sys
import re
import math
from collections import Counter


def emit(type_, **kwargs):
    print(json.dumps({"type": type_, **kwargs}, ensure_ascii=False), flush=True)


# Common stop words (English + Czech basics) to filter out
STOP_WORDS = {
    "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
    "have", "has", "had", "do", "does", "did", "will", "would", "shall",
    "should", "may", "might", "must", "can", "could", "of", "in", "to",
    "for", "with", "on", "at", "from", "by", "as", "into", "through",
    "during", "before", "after", "above", "below", "between", "and",
    "but", "or", "nor", "not", "so", "yet", "both", "either", "neither",
    "each", "every", "all", "any", "few", "more", "most", "other", "some",
    "such", "no", "only", "own", "same", "than", "too", "very", "just",
    "that", "this", "these", "those", "it", "its", "i", "me", "my",
    "we", "our", "you", "your", "he", "him", "his", "she", "her",
    "they", "them", "their", "what", "which", "who", "whom", "when",
    "where", "how", "why", "if", "then", "else", "up", "out", "off",
    "over", "under", "again", "further", "once", "here", "there", "about",
    # Czech basics
    "a", "je", "v", "na", "se", "z", "do", "pro", "s", "ke", "za",
    "po", "od", "o", "k", "i", "že", "ale", "to", "jak", "tak",
    "při", "nebo", "ten", "ta", "ty", "jsou", "jsem", "jste", "být",
    "který", "která", "které", "jeho", "její", "jejich", "co", "si"
}


def split_sentences(text):
    """Split text into sentences using regex."""
    # Split on sentence-ending punctuation followed by space or end
    sentences = re.split(r'(?<=[.!?])\s+', text)
    # Clean up
    result = []
    for s in sentences:
        s = s.strip()
        if len(s) > 10:  # Skip very short fragments
            result.append(s)
    return result


def tokenize(text):
    """Simple word tokenization."""
    return re.findall(r'\b\w+\b', text.lower())


def score_sentences(sentences, max_sentences):
    """Score sentences by TF-IDF-like relevance + position bonus."""
    if not sentences:
        return []

    # Build word frequency across all sentences
    all_words = []
    sentence_words = []
    for s in sentences:
        words = [w for w in tokenize(s) if w not in STOP_WORDS and len(w) > 2]
        sentence_words.append(words)
        all_words.extend(words)

    if not all_words:
        return list(range(min(max_sentences, len(sentences))))

    word_freq = Counter(all_words)
    max_freq = word_freq.most_common(1)[0][1] if word_freq else 1

    # Score each sentence
    scored = []
    n = len(sentences)
    for i, (sent, words) in enumerate(zip(sentences, sentence_words)):
        if not words:
            scored.append((i, 0.0))
            continue

        # TF score (normalized)
        tf_score = sum(word_freq[w] / max_freq for w in words) / len(words)

        # Position bonus: first and last sentences get a boost
        position_bonus = 0.0
        if i == 0:
            position_bonus = 0.3
        elif i == n - 1:
            position_bonus = 0.1
        elif i < n * 0.2:  # First 20% of text
            position_bonus = 0.15

        # Length penalty for very short sentences
        length_factor = min(1.0, len(words) / 5.0)

        score = (tf_score + position_bonus) * length_factor
        scored.append((i, score))

    # Sort by score descending, take top N
    scored.sort(key=lambda x: x[1], reverse=True)
    top_indices = sorted([idx for idx, _ in scored[:max_sentences]])

    return top_indices


def main():
    params = json.load(sys.stdin)

    text = params.get("text", "").strip()
    if not text:
        emit("result", status="error", output={"error": "Missing required parameter: text"})
        return

    max_sentences = int(params.get("max_sentences", 5))
    max_length = int(params.get("max_length", 1000))
    style = params.get("style", "paragraph").lower()

    # Split into sentences
    sentences = split_sentences(text)

    if not sentences:
        emit("result", status="success", output={
            "summary": text[:max_length],
            "sentence_count": 0,
            "original_length": len(text)
        })
        return

    if len(sentences) <= max_sentences:
        # Text is already short enough
        summary_sentences = sentences
    else:
        top_indices = score_sentences(sentences, max_sentences)
        summary_sentences = [sentences[i] for i in top_indices]

    # Format output
    if style == "bullets":
        summary = "\n".join(f"• {s}" for s in summary_sentences)
    else:
        summary = " ".join(summary_sentences)

    # Enforce max_length
    if len(summary) > max_length:
        summary = summary[:max_length - 3] + "..."

    emit("result", status="success", output={
        "summary": summary,
        "sentence_count": len(summary_sentences),
        "original_sentences": len(sentences),
        "original_length": len(text),
        "summary_length": len(summary),
        "compression_ratio": round(len(summary) / len(text), 2) if text else 0
    })


if __name__ == "__main__":
    main()
