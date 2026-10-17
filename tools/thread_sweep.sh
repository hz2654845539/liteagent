#!/bin/bash
# 线程数扫描：对每个线程数跑一轮 benchmark，汇总成对比表
#
# 前提：模型已经在设备上（files/models/ 下），App 已安装（release 或 debug）
# 用法：ADB=path/to/adb bash tools/thread_sweep.sh [模型ID] [线程列表] [输出目录]
#   例：bash tools/thread_sweep.sh Qwen2.5-0.5B-Instruct-MNN "2 3 4 5"
#
# 每组之间 force-stop 冷启动，保证不是接着上一组的热缓存跑的。
# 一轮 4 组约 7 分钟（每组加载 4s + 三条 prompt 约 55s + 启动与余量）。
set -euo pipefail

ADB=${ADB:-adb}
PKG=${PKG:-com.liteagent.app}
ACT="$PKG/com.liteagent.app.MainActivity"
MODEL=${1:-Qwen2.5-0.5B-Instruct-MNN}
THREADS=${2:-"2 3 4 5"}
OUT=${3:-.tmp/thread_sweep}
SETTLE=${SETTLE:-105}

mkdir -p "$OUT"
rm -f "$OUT"/*.json 2>/dev/null || true
"$ADB" shell "rm -rf /data/data/$PKG/files/benchmark" 2>/dev/null || true

for t in $THREADS; do
    echo "=== threads=$t ==="
    "$ADB" shell am force-stop "$PKG"
    sleep 2
    "$ADB" shell am start -n "$ACT" \
        --es autobench "1" --es automodel "$MODEL" --ei threads "$t" >/dev/null
    sleep "$SETTLE"
    n=$("$ADB" shell "ls /data/data/$PKG/files/benchmark/ 2>/dev/null | wc -l" | tr -d '\r')
    echo "  已产出 $n 份结果"
done

"$ADB" shell "chmod -R 644 /data/data/$PKG/files/benchmark/*" 2>/dev/null || true
"$ADB" pull "/data/data/$PKG/files/benchmark" "$OUT" 2>&1 | tail -1

python_bin=""
for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1; then python_bin=$c; break; fi
done
[ -z "$python_bin" ] && { echo "需要 python 汇总"; exit 1; }

"$python_bin" - "$OUT" <<'PY'
import glob, json, os, sys

out = sys.argv[1]
paths = sorted(glob.glob(os.path.join(out, "*.json")))
if not paths:
    sys.exit("没有结果文件")

rows = []
for p in paths:
    d = json.load(open(p, encoding="utf-8"))
    runs = d.get("runs", [])
    tps = [r["decodeTps"] for r in runs if "decodeTps" in r]
    if not tps:
        continue
    rows.append({
        "threads": d.get("threads"),
        "load": d.get("loadMs"),
        "avg": sum(tps) / len(tps),
        "each": tps,
    })

rows.sort(key=lambda r: r["threads"])
best = max(r["avg"] for r in rows)

print()
print(f"{'线程':<6}{'加载ms':<9}{'decode (各 prompt)':<32}{'平均':<8}{'相对最优'}")
print("-" * 70)
for r in rows:
    each = " / ".join(f"{v:.2f}" for v in r["each"])
    delta = (r["avg"] / best - 1) * 100
    tag = "基准" if abs(delta) < 1e-9 else f"{delta:+.1f}%"
    print(f"{r['threads']:<6}{r['load']:<9}{each:<32}{r['avg']:<8.2f}{tag}")

print()
print("注：decode 与输入长度无关，三条应接近；prefill 受 page cache 影响，此表不含。")
PY
