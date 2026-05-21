"""Row / column mean pooling for ColPali-style multi-vector outputs.

A ColPali (or ColQwen2 / ColSmolVLM) page embedding is shaped
``(n_tokens, dim)`` where ``n_tokens = grid_size*grid_size + n_special_tokens``:
the first ``grid_size*grid_size`` tokens correspond to image patches in a
square grid; the remaining tokens are model-specific specials (BOS, EOS,
separators).

Two pooling strategies, both averaging within the patch grid only and
preserving the special tokens unchanged:

- ``mean_pool_rows`` averages across columns (collapses 32×32 patches to 32
  row-averages).
- ``mean_pool_cols`` averages across rows (collapses to 32 column-averages).

After pooling, the special tokens are appended so the resulting tensor still
carries them — this matches the layout the ANN search expects in Qdrant's
``pooled_rows`` / ``pooled_cols`` named vectors.
"""

from __future__ import annotations

import math
from typing import Iterable


def mean_pool_rows(
    embedding: list[list[float]], grid_size: int = 32, n_special_tokens: int = 6
) -> list[list[float]]:
    """Average each row of the patch grid; append special tokens unchanged."""
    return _pool(embedding, grid_size, n_special_tokens, by_rows=True)


def mean_pool_cols(
    embedding: list[list[float]], grid_size: int = 32, n_special_tokens: int = 6
) -> list[list[float]]:
    """Average each column of the patch grid; append special tokens unchanged."""
    return _pool(embedding, grid_size, n_special_tokens, by_rows=False)


def _pool(
    embedding: list[list[float]],
    grid_size: int,
    n_special_tokens: int,
    by_rows: bool,
) -> list[list[float]]:
    if not embedding:
        return []
    n_tokens = len(embedding)
    n_patches = grid_size * grid_size
    if n_tokens < n_patches:
        # Different model — adapt by computing an effective grid_size from
        # the available patch count. We assume a square grid; if the patch
        # count isn't a perfect square, fall back to no pooling and return
        # the whole embedding (caller can handle).
        effective_grid = int(math.isqrt(max(n_tokens - n_special_tokens, 0)))
        if effective_grid * effective_grid + n_special_tokens > n_tokens:
            return list(embedding)
        grid_size = effective_grid
        n_patches = grid_size * grid_size
    if grid_size == 0:
        return list(embedding)

    patches = embedding[:n_patches]
    specials = embedding[n_patches:n_patches + n_special_tokens]

    dim = len(patches[0])
    pooled: list[list[float]] = []
    for major in range(grid_size):
        accum = [0.0] * dim
        for minor in range(grid_size):
            i = major * grid_size + minor if by_rows else minor * grid_size + major
            row = patches[i]
            for j in range(dim):
                accum[j] += row[j]
        pooled.append([v / grid_size for v in accum])

    pooled.extend(specials)
    return pooled


def n_special_tokens_for_model(model_name: str) -> int:
    """Best-effort number of special (non-patch) tokens per model family.

    ColPali / ColQwen2 / ColSmolVLM all emit a handful of trailing tokens
    (BOS / EOS / sep). Exact counts vary by model; ``6`` is a reasonable
    default that matches Qdrant's documented ColPali storage layout. If a
    deployment finds a precise count, override via configuration.
    """
    name = model_name.lower()
    if "colqwen" in name:
        return 6
    if "colpali" in name:
        return 6
    if "smolvlm" in name or "colsmol" in name:
        return 5
    return 6


def sanity_check_dims(embedding: Iterable[list[float]]) -> None:
    """Raise ``ValueError`` if the embedding's rows have inconsistent dims."""
    seen = -1
    for i, row in enumerate(embedding):
        if seen == -1:
            seen = len(row)
        elif len(row) != seen:
            raise ValueError(
                f"Inconsistent embedding row length at index {i}: "
                f"expected {seen}, got {len(row)}"
            )
