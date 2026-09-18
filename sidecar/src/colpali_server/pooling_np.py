"""Vectorized (numpy) twins of the pooling functions in ``pooling``.

``pooling`` is the reference: pure Python over ``list[list[float]]``, and the
parity test (``tests/test_pooling_np.py``) holds these to it within float32
rounding. Production uses these: the Python loops cost ~0.6 s of a 7 s
embed batch on the R530 (py-spy, 2026-09-17) and the ``tolist()`` they need
another 1.2 s; on a ``(n_tokens, dim)`` float32 array the same work is a
few milliseconds.

All functions take a 2-D array ``(n_tokens, dim)`` and return a 2-D float32
array. Sums accumulate in float64, like the Python reference (which adds
Python floats), then cast back to float32.
"""

from __future__ import annotations

import math

import numpy as np


def bucket_pool_np(embedding: np.ndarray, n_buckets: int = 32, strided: bool = False) -> np.ndarray:
    """Sequence pooling; see ``pooling.bucket_pool`` for the bucket rule."""
    n_tokens = int(embedding.shape[0])
    if n_tokens == 0 or n_tokens <= n_buckets:
        return np.asarray(embedding, dtype=np.float32)
    idx = np.arange(n_tokens)
    if strided:
        bucket = idx % n_buckets
    else:
        bucket = np.minimum(idx * n_buckets // n_tokens, n_buckets - 1)
    dim = embedding.shape[1]
    sums = np.zeros((n_buckets, dim), dtype=np.float64)
    np.add.at(sums, bucket, embedding.astype(np.float64, copy=False))
    counts = np.bincount(bucket, minlength=n_buckets).astype(np.float64)
    return (sums / counts[:, None]).astype(np.float32)


def mean_pool_rows_np(embedding: np.ndarray, grid_size: int = 32, n_special_tokens: int = 6) -> np.ndarray:
    return _pool_np(embedding, grid_size, n_special_tokens, by_rows=True)


def mean_pool_cols_np(embedding: np.ndarray, grid_size: int = 32, n_special_tokens: int = 6) -> np.ndarray:
    return _pool_np(embedding, grid_size, n_special_tokens, by_rows=False)


def _pool_np(embedding: np.ndarray, grid_size: int, n_special_tokens: int, by_rows: bool) -> np.ndarray:
    """Grid pooling; mirrors ``pooling._pool`` branch for branch."""
    n_tokens = int(embedding.shape[0])
    if n_tokens == 0:
        return np.zeros((0, 0), dtype=np.float32)
    n_patches = grid_size * grid_size
    if n_tokens < n_patches:
        effective_grid = int(math.isqrt(max(n_tokens - n_special_tokens, 0)))
        if effective_grid * effective_grid + n_special_tokens > n_tokens:
            return np.asarray(embedding, dtype=np.float32)
        grid_size = effective_grid
        n_patches = grid_size * grid_size
    if grid_size == 0:
        return np.asarray(embedding, dtype=np.float32)

    patches = embedding[:n_patches].astype(np.float64, copy=False)
    specials = embedding[n_patches:n_patches + n_special_tokens]
    grid = patches.reshape(grid_size, grid_size, -1)   # [major(row), minor(col), dim]
    # rows: average over columns (axis 1); cols: average over rows (axis 0).
    pooled = grid.mean(axis=1) if by_rows else grid.mean(axis=0)
    return np.concatenate([pooled.astype(np.float32), np.asarray(specials, dtype=np.float32)], axis=0)
