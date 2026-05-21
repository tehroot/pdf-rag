"""Unit tests for the row/col mean pooling functions."""

from __future__ import annotations

from colpali_server.pooling import (
    mean_pool_cols,
    mean_pool_rows,
    n_special_tokens_for_model,
    sanity_check_dims,
)


def _make_grid(grid_size: int, dim: int, n_special: int = 6) -> list[list[float]]:
    """Build an embedding where each patch is (row * grid + col) repeated dim times.

    Lets us reason about pooling outputs in closed form.
    """
    n_patches = grid_size * grid_size
    embedding: list[list[float]] = []
    for i in range(n_patches):
        embedding.append([float(i)] * dim)
    for s in range(n_special):
        embedding.append([-1.0 - s] * dim)
    return embedding


def test_mean_pool_rows_returns_grid_size_plus_specials():
    grid = 32
    emb = _make_grid(grid, dim=4, n_special=6)
    out = mean_pool_rows(emb, grid_size=grid, n_special_tokens=6)
    assert len(out) == grid + 6   # 32 row averages + 6 specials
    assert all(len(row) == 4 for row in out)


def test_mean_pool_rows_averages_each_row_correctly():
    grid = 4
    dim = 2
    emb = _make_grid(grid, dim=dim, n_special=0)
    # patch i has value i. row r has columns r*grid .. r*grid+grid-1.
    # mean over a row is (r*grid + (grid-1)/2).
    out = mean_pool_rows(emb, grid_size=grid, n_special_tokens=0)
    assert len(out) == grid
    for r in range(grid):
        expected = r * grid + (grid - 1) / 2
        for v in out[r]:
            assert abs(v - expected) < 1e-9


def test_mean_pool_cols_averages_each_column_correctly():
    grid = 4
    dim = 2
    emb = _make_grid(grid, dim=dim, n_special=0)
    # column c has rows c, c+grid, c+2*grid, ... mean is c + (grid-1)*grid/2 = c + grid*(grid-1)/2
    out = mean_pool_cols(emb, grid_size=grid, n_special_tokens=0)
    assert len(out) == grid
    for c in range(grid):
        expected = c + grid * (grid - 1) / 2
        for v in out[c]:
            assert abs(v - expected) < 1e-9


def test_mean_pool_preserves_special_tokens_unchanged():
    grid = 4
    emb = _make_grid(grid, dim=2, n_special=3)
    out_rows = mean_pool_rows(emb, grid_size=grid, n_special_tokens=3)
    # Last 3 entries should match the specials (-1, -2, -3).
    assert out_rows[-3] == [-1.0, -1.0]
    assert out_rows[-2] == [-2.0, -2.0]
    assert out_rows[-1] == [-3.0, -3.0]


def test_pool_handles_empty_input():
    assert mean_pool_rows([], grid_size=32, n_special_tokens=6) == []
    assert mean_pool_cols([], grid_size=32, n_special_tokens=6) == []


def test_pool_adapts_to_unexpected_token_count():
    # If the input has fewer tokens than the configured grid expects, fall
    # back to returning the whole input rather than throwing.
    emb = _make_grid(grid_size=4, dim=2, n_special=2)   # 16 patches + 2 special = 18 tokens
    # Caller passes grid=32 (1024+6 = 1030 expected), but we have only 18.
    out = mean_pool_rows(emb, grid_size=32, n_special_tokens=6)
    # No usable grid → return-as-is or adapt; either way result is non-empty
    # and well-formed.
    assert isinstance(out, list)
    assert all(isinstance(r, list) for r in out)


def test_n_special_tokens_for_model_branches_by_name():
    assert n_special_tokens_for_model("vidore/colqwen2-v1.0") == 6
    assert n_special_tokens_for_model("vidore/colpali-v1.2") == 6
    assert n_special_tokens_for_model("vidore/colsmolvlm-v0.1") == 5
    # Unknown model → conservative default.
    assert n_special_tokens_for_model("custom/unknown") == 6


def test_sanity_check_dims_passes_consistent():
    sanity_check_dims([[1.0, 2.0, 3.0], [4.0, 5.0, 6.0]])


def test_sanity_check_dims_raises_on_inconsistent():
    import pytest

    with pytest.raises(ValueError, match="Inconsistent"):
        sanity_check_dims([[1.0, 2.0], [3.0, 4.0, 5.0]])
