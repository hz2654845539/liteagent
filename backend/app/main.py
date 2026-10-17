"""LiteAgent 后端。

职责边界：**不做推理**。推理全部在端侧完成，这里只负责三件事：
  1. 下发模型清单（客户端内置一份，后端可覆盖）
  2. 服务端推荐（与客户端读同一份 rules/recommend-rules.json）
  3. 回收性能埋点

为什么保留服务端推荐：客户端内置规则无法热更新，一旦发现某机型需要特殊参数，
后端改 JSON 就能生效，不用发版。客户端拉不到时回落本地规则。
"""

from __future__ import annotations

import json
import sqlite3
import time
from functools import lru_cache
from pathlib import Path
from typing import List, Optional

from fastapi import FastAPI
from pydantic import BaseModel, Field

ROOT = Path(__file__).resolve().parents[2]
RULES_PATH = ROOT / "rules" / "recommend-rules.json"
CATALOG_PATH = ROOT / "app-android" / "src" / "main" / "assets" / "model-catalog.json"
DB_PATH = Path(__file__).resolve().parent / "liteagent.db"

app = FastAPI(title="LiteAgent Backend", version="0.1.0")


# ---------------------------------------------------------------- 规则

@lru_cache(maxsize=1)
def load_rules() -> dict:
    """规则表缓存。

    每次 /models/recommend 都读一遍 JSON 是没必要的 IO；规则变更本来就伴随服务重启
    （它是部署产物的一部分，不是运行时热更新的配置）。改了规则记得重启。
    """
    with RULES_PATH.open(encoding="utf-8") as f:
        return json.load(f)


@lru_cache(maxsize=1)
def load_catalog() -> dict:
    if not CATALOG_PATH.exists():
        return {"version": 1, "models": []}
    with CATALOG_PATH.open(encoding="utf-8") as f:
        return json.load(f)


def nominal_gb(total_mem_mb: int, rules: dict) -> int:
    """把实测总内存归并到标称档位。

    与客户端 RecommendEngine.nominalGb 同构：totalMem 比标称小 0.4-0.8GB，
    不补偿的话 6GB 机会掉进 0.5B 档、>=12GB 档永远命中不了。
    """
    adjusted = total_mem_mb / 1024.0 + 0.6
    tiers = rules["memTiers"]
    return min(tiers, key=lambda t: abs(t["nominalGb"] - adjusted))["nominalGb"]


def pick_threads(cores: int, rules: dict) -> int:
    if cores <= 0:
        return rules["threads"]["defaultCores"]
    return max(1, min(cores, rules["threads"]["maxCores"]))


def can_use_gpu(gles: str, renderer: Optional[str], rules: dict) -> bool:
    try:
        major = int(gles.split(".")[0])
    except (ValueError, AttributeError):
        return False
    if major < rules["gpu"]["minGlesMajor"]:
        return False
    if not renderer:
        return False
    return any(renderer.lower().startswith(p.lower()) for p in rules["gpu"]["supportedRendererPrefixes"])


# ---------------------------------------------------------------- 模型

class DeviceProfile(BaseModel):
    socModel: Optional[str] = None
    manufacturer: str = ""
    brand: str = ""
    board: str = ""
    hardware: str = ""
    cpuCores: int = 0
    cpuMaxFreqMhz: Optional[int] = None
    totalMemMb: int = 0
    availMemMb: int = 0
    memoryClassMb: int = 0
    maxHeapMb: int = 0
    freeStorageMb: int = 0
    sdkInt: int = 0
    supportedAbis: List[str] = Field(default_factory=list)
    glesVersion: str = ""
    gpuRenderer: Optional[str] = None
    isArm64: bool = False
    collectedAt: int = 0
    failures: List[str] = Field(default_factory=list)


class Recommendation(BaseModel):
    supported: bool
    # 极限档：参数压到最低，能跑但慢
    restricted: bool = False
    modelId: Optional[str] = None
    quantization: Optional[str] = None
    contextLength: Optional[int] = None
    threads: Optional[int] = None
    useGpu: Optional[bool] = None
    useMmap: Optional[bool] = None
    reason: str
    source: str = "REMOTE_OVERRIDE"


class PerfReport(BaseModel):
    deviceFingerprint: str
    modelId: str
    quantization: str = ""
    threads: int = 0
    useGpu: bool = False
    loadMs: int = 0
    prefillMs: int = 0
    ttftMs: int = 0
    decodeTps: float = 0.0
    peakMemMb: int = 0
    sustainedTps: Optional[float] = None
    promptTokens: int = 0
    generatedTokens: int = 0
    timestamp: int = 0


# ---------------------------------------------------------------- 接口

@app.get("/health")
def health() -> dict:
    return {"status": "ok", "time": int(time.time())}


@app.get("/models")
def models() -> dict:
    return load_catalog()


@app.post("/models/recommend", response_model=Recommendation)
def recommend(profile: DeviceProfile) -> Recommendation:
    rules = load_rules()

    if 0 < profile.totalMemMb < rules["unsupported"]["belowTotalMemMb"]:
        return Recommendation(
            supported=False,
            reason=rules["unsupported"]["message"].replace(
                "X", f"{profile.totalMemMb / 1024:.1f}GB"
            ),
        )

    extreme = rules.get("extreme")
    if extreme and extreme["aboveTotalMemMb"] <= profile.totalMemMb < extreme["belowTotalMemMb"]:
        return Recommendation(
            supported=True,
            restricted=True,
            modelId=extreme["modelId"],
            quantization="W4",
            contextLength=extreme["contextLength"],
            threads=max(1, min(extreme["threads"], profile.cpuCores or extreme["threads"])),
            # 极限档强制关 GPU、强制开 mmap，理由见 docs/plan-low-end-device.md
            useGpu=False,
            useMmap=True,
            reason=f"{profile.totalMemMb / 1024:.1f}GB 内存 · {extreme['reason']}",
            source="REMOTE_OVERRIDE_EXTREME",
        )

    tier = next(
        (t for t in rules["memTiers"] if t["nominalGb"] == nominal_gb(profile.totalMemMb, rules)),
        rules["memTiers"][0],
    )
    threads = pick_threads(profile.cpuCores, rules)
    use_gpu = can_use_gpu(profile.glesVersion, profile.gpuRenderer, rules)
    mmap_rule = rules["mmap"]
    use_mmap = (
        0 < profile.totalMemMb < mmap_rule["enabledWhenTotalMemMbBelow"]
        and profile.freeStorageMb > mmap_rule["enabledWhenFreeStorageMbAbove"]
    )

    parts = [f"{profile.totalMemMb / 1024:.1f}GB 内存"]
    if profile.socModel:
        parts.append(profile.socModel)
    if use_gpu:
        parts.append("开启 GPU(OpenCL)")
    if use_mmap:
        parts.append("mmap 加载")
    parts.append(tier["reason"])

    return Recommendation(
        supported=True,
        modelId=tier["modelId"],
        quantization=tier["quantization"],
        contextLength=tier["contextLength"],
        threads=threads,
        useGpu=use_gpu,
        useMmap=use_mmap,
        reason=" · ".join(parts),
    )


def _db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS perf (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_fingerprint TEXT NOT NULL,
            model_id TEXT NOT NULL,
            quantization TEXT,
            threads INTEGER,
            use_gpu INTEGER,
            load_ms INTEGER,
            prefill_ms INTEGER,
            ttft_ms INTEGER,
            decode_tps REAL,
            peak_mem_mb INTEGER,
            sustained_tps REAL,
            prompt_tokens INTEGER,
            generated_tokens INTEGER,
            created_at INTEGER
        )
        """
    )
    conn.commit()
    return conn


@app.post("/perf")
def post_perf(report: PerfReport) -> dict:
    # 注意：sqlite3.Connection 的 with 只管事务提交，不关连接。
    # 每个请求泄漏一个连接，压测时会耗尽句柄，所以这里显式 try/finally。
    conn = _db()
    try:
        cur = conn.execute(
            """INSERT INTO perf (device_fingerprint, model_id, quantization, threads, use_gpu,
               load_ms, prefill_ms, ttft_ms, decode_tps, peak_mem_mb, sustained_tps,
               prompt_tokens, generated_tokens, created_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                report.deviceFingerprint,
                report.modelId,
                report.quantization,
                report.threads,
                int(report.useGpu),
                report.loadMs,
                report.prefillMs,
                report.ttftMs,
                report.decodeTps,
                report.peakMemMb,
                report.sustainedTps,
                report.promptTokens,
                report.generatedTokens,
                report.timestamp or int(time.time()),
            ),
        )
        conn.commit()
        row_id = cur.lastrowid
    finally:
        conn.close()
    return {"id": row_id, "saved": True}


@app.get("/perf")
def list_perf(limit: int = 50) -> dict:
    conn = _db()
    try:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            "SELECT * FROM perf ORDER BY id DESC LIMIT ?", (limit,)
        ).fetchall()
        items = [dict(r) for r in rows]
    finally:
        conn.close()
    return {"items": items, "count": len(items)}
