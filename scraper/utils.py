"""Shared utility helpers."""

from __future__ import annotations

import logging
import re
import time
from urllib.parse import urlparse

logger = logging.getLogger(__name__)


def extract_domain(url: str) -> str:
    """Return the bare domain (no www, no scheme) from a URL."""
    try:
        parsed = urlparse(url)
        hostname = parsed.hostname or ""
        return hostname.removeprefix("www.")
    except Exception:
        return ""


def is_shopify_store(domain: str) -> bool:
    """Heuristic: myshopify.com subdomain or known Shopify TLD pattern."""
    return "myshopify.com" in domain or domain.endswith(".shop")


def sanitize_filename(name: str) -> str:
    """Strip characters unsafe in file/path names."""
    return re.sub(r"[^\w\-_.]", "_", name).lower()


def format_elapsed(seconds: float) -> str:
    """Human-readable duration string, e.g. '2m 34s'."""
    seconds = int(seconds)
    m, s = divmod(seconds, 60)
    return f"{m}m {s:02d}s" if m else f"{s}s"


class Timer:
    """Context manager that measures wall-clock time for a labelled step."""

    def __init__(self, label: str) -> None:
        self.label = label
        self._start: float = 0.0
        self.elapsed: float = 0.0

    def __enter__(self) -> "Timer":
        self._start = time.perf_counter()
        return self

    def __exit__(self, *_) -> None:
        self.elapsed = time.perf_counter() - self._start
        logger.info("[%s] done in %s", self.label, format_elapsed(self.elapsed))


def setup_logging(level: str = "INFO") -> None:
    """Configure root logger for GitHub Actions friendly output."""
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
        datefmt="%H:%M:%S",
    )
