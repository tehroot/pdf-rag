"""Parity gate: the numpy pooling must reproduce the Python reference.

Vectors already indexed were pooled by ``pooling.py``; if ``pooling_np.py``
drifted, new pages would score differently from old ones at search time.
Tolerance is float32 rounding (the reference sums Python floats, i.e.
float64, then the wire carried float32-representable values).
"""

from __future__ import annotations

import numpy as np
import pytest

from colpali_server.pooling import bucket_pool, mean_pool_cols, mean_pool_rows
from colpali_server.pooling_np import bucket_pool_np, mean_pool_cols_np, mean_pool_rows_np

RTOL = 1e-6
ATOL = 1e-6


def _rand(n_tokens: int, dim: int, seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    # Values like real embeddings: unit-ish, signed, float32 on the wire.
    return rng.standard_normal((n_tokens, dim)).astype(np.float32) / np.sqrt(dim)


def _check(ref: list[list[float]], got: np.ndarray) -> None:
    assert got.dtype == np.float32
    if not ref:
        assert got.size == 0   # empty input: shape is (0, dim) vs [] — both carry nothing
        return
    ref_a = np.asarray(ref, dtype=np.float64)
    assert got.shape == ref_a.shape, (got.shape, ref_a.shape)
    np.testing.assert_allclose(got.astype(np.float64), ref_a, rtol=RTOL, atol=ATOL)


@pytest.mark.parametrize("n_tokens", [1251, 1280, 640, 33, 32, 31, 5, 0])
@pytest.mark.parametrize("strided", [False, True])
def test_bucket_pool_matches_reference(n_tokens: int, strided: bool) -> None:
    a = _rand(n_tokens, 16, seed=n_tokens + int(strided))
    ref = bucket_pool(a.tolist(), n_buckets=32, strided=strided)
    got = bucket_pool_np(a, n_buckets=32, strided=strided)
    _check(ref, got)


@pytest.mark.parametrize("n_tokens", [1030, 1024 + 6, 1024, 70, 64 + 6, 20, 6, 1, 0])
def test_grid_pool_matches_reference(n_tokens: int) -> None:
    a = _rand(n_tokens, 8, seed=n_tokens)
    for ref_fn, np_fn in ((mean_pool_rows, mean_pool_rows_np), (mean_pool_cols, mean_pool_cols_np)):
        ref = ref_fn(a.tolist(), grid_size=32, n_special_tokens=6)
        got = np_fn(a, grid_size=32, n_special_tokens=6)
        if n_tokens == 0:
            assert got.size == 0 and ref == []
        else:
            _check(ref, got)


def test_grid_pool_rows_and_cols_differ_on_asymmetric_grid() -> None:
    # Guard against a transposed axis: rows and cols must not coincide.
    a = _rand(64 + 6, 4, seed=7)
    rows = mean_pool_rows_np(a, grid_size=8, n_special_tokens=6)
    cols = mean_pool_cols_np(a, grid_size=8, n_special_tokens=6)
    assert rows.shape == cols.shape == (8 + 6, 4)
    assert not np.allclose(rows[:8], cols[:8])
    np.testing.assert_array_equal(rows[8:], a[64:70])   # specials pass through
