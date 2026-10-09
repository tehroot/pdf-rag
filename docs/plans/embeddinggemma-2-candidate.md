# Candidate: EmbeddingGemma-2 as an embedder (findings, v0)

Status: findings only. No code, no branch. Revisit before any experiment.
Recorded: 2026-10-07

## Question

How does [google/embeddinggemma-2](https://huggingface.co/google/embeddinggemma-2)
compare with the live visual model,
[TomoroAI/tomoro-colqwen3-embed-4b](https://huggingface.co/TomoroAI/tomoro-colqwen3-embed-4b)
at revision `bf790bd`?

## Answer

EmbeddingGemma-2 does not replace tomoro-colqwen3. The two models do
different work. EmbeddingGemma-2 is a better match for the **text side**,
where `dtic_archive` runs bge-small-en-v1.5 (33M parameters, 384-d) now.

## Model facts

Source: the two Hugging Face model cards, read 2026-10-07 through a fetch
tool that summarizes the page. Confirm the numbers on the cards before you
use them in a design decision.

| | EmbeddingGemma-2 | tomoro-colqwen3-embed-4b (live) |
|---|---|---|
| Parameters | 740M total: 270M text, 170M vision, 300M audio. You can load the text encoder by itself. | 4B |
| Modalities | Text, image, video, audio in one embedding space | Image (page), text query; video as a side effect |
| Output | **One 768-d vector** for each input. Matryoshka truncation to 512, 256, 128. Card: "close to lossless down to 256". | **Many vectors**: up to 1,280 × 320-d for each page, MaxSim (late interaction) |
| Image budget | 280 tokens default, adjustable 70 to 1,120 | 1,280 visual tokens |
| Text context | 8,192 tokens, shared across modalities | n/a (embeds the page image) |
| Visual-document score | MMEB v2 VisDoc nDCG@5 = 67.84 | ViDoRe V1 0.9057, V2 0.6598, V3 0.5934 (English nDCG@5 averages) |
| Text score | MTEB (eng, v2) 68.46; MTEB multilingual 61.36; code 78.68 | n/a |
| Precision | bf16 or f32 only. Card: f16 gives NaN or degraded output. | bf16 |
| Prompt format | Query: `task: search result \| query: {query}`. Document: `title: {title} \| text: {content}`. Card: no prefix reduces quality. | colpali-engine processor |
| Training data cutoff | January 2025 | not recorded |
| License | Apache 2.0 | Apache 2.0 |

**The two visual scores are not comparable.** MMEB v2 VisDoc is a mix of
ViDoRe and other datasets. ViDoRe numbers are per-version averages. Neither
card shows a direct comparison of the two models. No published number tells
which model ranks DTIC pages better.

Unverified: the earlier EmbeddingGemma-300M scored about 68.4 on MTEB eng v2
(from memory, not checked). If that is correct, version 2 gives no large
English text gain over version 1. Its gains are multilingual, code, and the
added modalities.

## Option A: replace ColQwen on the visual side. Not recommended.

- One 768-d vector from 280 image tokens holds a full page. DTIC pages are
  dense scanned reports. Small print is the signal that late interaction
  keeps and one pooled vector loses.
- Single-vector models usually score below ColQwen-class models on ViDoRe.
  This is a general pattern, not a measurement on this corpus.
- It is much cheaper: a 170M vision encoder at 280 tokens against a 4B model
  at 1,280 tokens.

### Sub-option A2: cheap first-stage page prefetch

One 768-d f32 vector for each of the 934,834 pages is about 2.9 GB (about
0.96 GB at 256-d). EmbeddingGemma-2 could be the first-stage candidate
generator, with the tomoro `original` vectors for the rerank. This competes
with the pooled-vector prefetch and with the GPU prefetch work in
`docs/accelerators/`.

Blocker: a relevance test is necessary. The recall assets in
`/srv/pdf-corpus/ingest/recall/` measure pooled-vector recall against exact
search on the same model. They do not measure relevance, and they cannot
score a different model's candidates. A cross-model test needs ground truth
that does not depend on one model: a gold query set, or tomoro full MaxSim
top-K as a proxy truth.

## Option B: replace bge-small on the text side. Candidate.

Advantages:

- **8,192-token context against bge's 512.** This removes the reason for
  `INGEST_CHUNK_SIZE_CHARS=700` and the truncation path in
  `Embedder.embedWithTruncation`.
- Stronger general text scores than bge-small (no same-benchmark number
  recorded here).
- Matryoshka truncation keeps storage near the current size. The card lists
  512, 256 and 128 as truncation points; 384 is not listed.

Costs and risks:

| Item | Detail |
|---|---|
| Full re-embed | Collection dim is immutable. All 2,012,201 chunks go into a new collection. Dense f32 storage: 768-d ≈ 6.2 GB, 512-d ≈ 4.1 GB, 256-d ≈ 2.1 GB. bge-small 384-d now ≈ 3.1 GB. |
| Compute | 270M against 33M parameters: about 8× the work per token. Longer chunks add more. Measure on the A4500 before a full re-embed. |
| Serving | llama-server support for this multimodal architecture is **not verified**. The common GGUF export is f16, and the card warns against f16. bf16 or f32 serving is necessary. The project chose llama-server for embeddings; a different server changes that decision. |
| Prefixes | `Embedder` sends raw text for both queries and documents. It needs a query/document split with the two task prefixes. The search path and both ingest pipelines call it. |
| Evaluation | `docs/eval/retrieval-eval.md` has never run on a real gold set. A model swap needs that set to show a gain. |
| Chunk size | A larger context changes the best chunk size. Re-tune `INGEST_CHUNK_SIZE_CHARS` and overlap together with the model, not after. |

## Recommendation

1. Keep tomoro-colqwen3 for the visual index.
2. Keep Option B as the experimental branch candidate.
3. Keep Option A2 as a note for the prefetch work. Do not start it before
   a cross-model relevance test exists.

## First steps for an experimental branch (Option B)

1. Confirm llama.cpp support for EmbeddingGemma-2 and a bf16 or f32 GGUF.
   If no support, decide on a different server before more work.
2. Add query/document prefix support to `Embedder` behind configuration.
   Default stays off, so bge behavior does not change.
3. Build a gold query set for a DTIC sample (the eval harness needs it in
   all cases).
4. Ingest the sample into two fresh KBs: bge-small at 700 chars, and
   EmbeddingGemma-2 at a larger chunk size. Compare hit@K and MRR with
   `docs/eval/retrieval-eval.md`.
5. Measure embed throughput on the A4500 next to the sidecar's VRAM
   reservation (see `docs/plans/gpu-text-embedder-v1.md`).
6. Only after a measured gain: plan the `dtic_archive` re-embed.
