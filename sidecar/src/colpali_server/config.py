"""Environment-driven configuration for the ColPali sidecar."""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """All sidecar config in one place.

    Loaded from process environment with the ``COLPALI_`` prefix. See the
    project README for the canonical list of variables.
    """

    model_config = SettingsConfigDict(
        env_prefix="COLPALI_",
        env_file=None,
        extra="ignore",
        case_sensitive=False,
    )

    # Model selection
    model: str = "vidore/colqwen2-v1.0"
    # Pin the checkpoint to one git revision on the Hub. Empty = whatever
    # "main" is today, which is NOT stable for trust_remote_code models: the
    # repo ships executable modeling code, so an upstream push changes what
    # this process runs with no change on our side. That is exactly how
    # tomoro-colqwen3 broke — see the README and .env.example for the
    # known-good hash. Pin it in any deployment you care about.
    model_revision: str = ""
    device: str = "auto"  # "cuda" | "cpu" | "mps" | "auto"
    dtype: str = "bfloat16"  # "float32" | "float16" | "bfloat16"

    # Inference parameters
    max_batch_size: int = 8
    pool_grid: int = 32  # the patch grid side used for row/col pooling
    # Dynamic-resolution cap for Qwen3-VL-class processors (tomoro handle).
    # Bounds visual tokens per page — without it a dense page can exceed
    # 12k tokens and OOM the batch. Ignored by grid-based (colpali-engine)
    # models, which cap resolution in their own presets.
    max_visual_tokens: int = 1280
    # Attention implementation for trust_remote_code models: "sdpa" works
    # everywhere; "flash_attention_2" is faster but needs flash-attn installed.
    attn_impl: str = "sdpa"

    # HTTP server bind
    host: str = "0.0.0.0"
    port: int = 8090

    # Operational
    enable_pooled: bool = True
    enable_original: bool = True


_settings: Settings | None = None


def settings() -> Settings:
    """Return the process-wide settings singleton.

    Built lazily on first call so import of this module remains cheap and
    pytest fixtures can override env vars before settings materialize.
    """
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings


def reset_settings_for_testing() -> None:
    """Drop the cached settings singleton (test-only helper)."""
    global _settings
    _settings = None
