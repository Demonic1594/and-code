#!/usr/bin/env python3
"""Regenerates app/src/main/assets/pricing/models-dev-snapshot.json from models.dev.

The snapshot is the seed of the Usage page's list-price estimates: a filtered copy of
https://models.dev/api.json (provider, model, and the four per-million-token prices only),
written in PriceCatalog's own field names so PriceCatalog.parseSnapshot reads it directly.
Prices come from the API at generation time; fetchedAt is the generation moment, so a fresh
build's seed counts as fresh for a week before the first network refresh.

Usage: scripts/update_models_dev_snapshot.py [api.json]
Without an argument the current api.json is downloaded.
"""

import json
import sys
import time
import urllib.request
from pathlib import Path

API_URL = "https://models.dev/api.json"
TARGET = Path("app/src/main/assets/pricing/models-dev-snapshot.json")


def load_source() -> str:
    if len(sys.argv) > 1:
        return Path(sys.argv[1]).read_text()
    with urllib.request.urlopen(API_URL, timeout=60) as response:
        return response.read().decode()


def main() -> None:
    root = json.loads(load_source())
    prices: dict[str, dict[str, dict[str, float]]] = {}
    models = 0
    for provider_id, provider in root.items():
        if provider_id.startswith("_"):
            continue
        priced: dict[str, dict[str, float]] = {}
        for model_id, model in (provider.get("models") or {}).items():
            cost = model.get("cost") or {}
            entry = {}
            for key, field in (
                ("input", "input"),
                ("output", "output"),
                ("cache_read", "cacheRead"),
                ("cache_write", "cacheWrite"),
            ):
                try:
                    entry[field] = round(float(cost.get(key, 0)), 6)
                except (TypeError, ValueError):
                    pass
            if entry:
                priced[model_id] = entry
        if priced:
            prices[provider_id] = priced
            models += len(priced)
    snapshot = {"fetchedAt": int(time.time() * 1000), "prices": prices}
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(json.dumps(snapshot, separators=(",", ":")))
    print(f"{TARGET}: {len(prices)} providers, {models} models, {TARGET.stat().st_size} bytes")


if __name__ == "__main__":
    main()
