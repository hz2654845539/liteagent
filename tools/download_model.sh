#!/bin/bash
# 下载 MNN 预转换模型（ModelScope 直链，支持断点续传 + 逐文件校验）
#
# 为什么不用 modelscope CLI：它要装 Python 依赖；这里直接用 HTTP 直链，
# 断点续传（curl -C -）和校验都能自己控制，也正好对应 App 里模型管理要做的逻辑。
#
# 文件列表从 model-catalog.json 读，不写死。原因见下：
#   三个模型的组成并不一样 —— 0.5B 有独立的 embeddings_bf16.bin（272MB），
#   1.5B 没有（它有 tie_embeddings，词嵌打包在权重里）。
#   写死列表的后果：去下 1.5B 的 embeddings_bf16.bin，ModelScope 会返回一段
#   145 字节的错误 JSON 而不是 404，于是目录里多了一个看起来正常的假文件。
#
# 用法：bash tools/download_model.sh [模型ID] [目标目录]
#   模型ID 形如 MNN/Qwen2.5-0.5B-Instruct-MNN
set -euo pipefail

MODEL_ID=${1:-MNN/Qwen2.5-0.5B-Instruct-MNN}
NAME=$(basename "$MODEL_ID")
DEST=${2:-models/$NAME}
BASE="https://modelscope.cn/models/${MODEL_ID}/resolve/master"
CATALOG="$(cd "$(dirname "$0")/.." && pwd)/app-android/src/main/assets/model-catalog.json"

python_bin=""
for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1; then
        python_bin=$candidate
        break
    fi
done

if [ -z "$python_bin" ]; then
    echo "需要 python 解析 model-catalog.json"
    exit 1
fi

if [ ! -f "$CATALOG" ]; then
    echo "找不到清单: $CATALOG"
    exit 1
fi

# 从清单读这个模型的文件名与大小
manifest=$("$python_bin" - "$CATALOG" "$NAME" <<'PY'
import json, sys
catalog_path, name = sys.argv[1], sys.argv[2]
data = json.load(open(catalog_path, encoding="utf-8"))
matched = [m for m in data["models"] if m["id"] == name]
if not matched:
    known = ", ".join(m["id"] for m in data["models"])
    sys.exit(f"清单里没有 {name}。已知: {known}")
for f in matched[0]["files"]:
    print(f'{f["name"]}\t{f["size"]}')
PY
)

if [ -z "$manifest" ]; then
    echo "清单解析为空，检查 model-catalog.json"
    exit 1
fi

filesize() {
    stat -c %s "$1" 2>/dev/null || stat -f %z "$1" 2>/dev/null || echo 0
}

mkdir -p "$DEST"

echo "模型: $NAME"
echo "目标: $DEST"

while IFS=$'\t' read -r fname want; do
    # Windows 上 python 输出的是 CRLF，read 只按 \n 切，字段尾会残留 \r，
    # 结果 "159" != "159\r" 永远成立，每个文件都被判成需要下载
    fname=${fname%$'\r'}
    want=${want%$'\r'}
    [ -z "$fname" ] && continue
    have=0
    if [ -f "$DEST/$fname" ]; then
        have=$(filesize "$DEST/$fname")
    fi

    if [ "$have" = "$want" ]; then
        echo "  ✓ 已完整: $fname ($want bytes)"
        continue
    fi

    echo "  下载: $fname (已有 $have / 需要 $want)"
    curl -sSL --retry 3 --retry-delay 2 --connect-timeout 20 -C - \
        -o "$DEST/$fname" "$BASE/$fname"

    got=$(filesize "$DEST/$fname")
    if [ "$got" != "$want" ]; then
        echo "  ✗ 大小不符: $fname 期望 $want 实际 $got"
        echo "    模型仓库里可能没有这个文件（ModelScope 会返回错误 JSON 而不是 404）："
        head -c 160 "$DEST/$fname" 2>/dev/null
        echo
        rm -f "$DEST/$fname"
        exit 1
    fi
done <<< "$manifest"

echo "==================== 结果 ===================="
echo "全部 $(echo "$manifest" | wc -l | tr -d ' ') 个文件校验通过"
du -sh "$DEST"
