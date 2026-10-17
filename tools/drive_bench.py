"""驱动设备跑一轮 benchmark，并把结果取回来。

为什么要写脚本：这台设备的触摸屏拿不到输入法连接，控制中心还会卡住全屏，
手工 input tap 极不稳定。用 uiautomator dump 定位控件再点，可重复。

用法：
    python tools/drive_bench.py
"""

import re
import subprocess
import sys
import time
from pathlib import Path

ADB = r"C:\Users\dnake\Downloads\platform-tools-latest-windows\platform-tools\adb.exe"
PKG = "com.liteagent.app.debug"
ACT = f"{PKG}/com.liteagent.app.MainActivity"
ROOT = Path(__file__).resolve().parents[1]


def sh(*args, timeout=120):
    return subprocess.run(
        [ADB, *args], capture_output=True, text=True, timeout=timeout, errors="ignore"
    ).stdout


def dump_xml():
    sh("shell", "uiautomator", "dump", "/sdcard/_d.xml")
    return sh("shell", "cat", "/sdcard/_d.xml")


def find(xml, *keywords):
    """返回第一个同时包含所有关键词的控件的中心坐标"""
    for node in re.findall(r"<node[^>]*>", xml):
        text_m = re.search(r'text="([^"]*)"', node)
        bounds_m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if not text_m or not bounds_m:
            continue
        text = text_m.group(1)
        if all(k in text for k in keywords):
            x1, y1, x2, y2 = map(int, bounds_m.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def tap(x, y):
    # 顶部约 60px 是系统手势区，点在 y<60 会被 systemui 吞掉。
    # 实测同一个 Tab 点 y=29 无效、y=40 有效，所以这里统一往下挪。
    if y < 60:
        y += 15
    sh("shell", "input", "tap", str(x), str(y))


def tap_text(*keywords, timeout=30):
    """轮询直到找到控件并点击，返回是否成功"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        pos = find(dump_xml(), *keywords)
        if pos:
            tap(*pos)
            print(f"  点击 {'/'.join(keywords)} @ {pos}")
            return True
        time.sleep(1.5)
    print(f"  超时：没找到 {'/'.join(keywords)}")
    return False


def wait_text(*keywords, timeout=240):
    """轮询直到界面上出现某段文字"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if find(dump_xml(), *keywords):
            return True
        time.sleep(2)
    return False


def main():
    print("[1] 重启应用")
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1)
    sh("shell", "am", "start", "-n", ACT)
    time.sleep(5)
    # systemui 重启或刚 force-stop 后，窗口可能拿不到焦点，
    # 此时 uiautomator dump 返回空树。点一下空白区让它拿到焦点。
    sh("shell", "input", "tap", "640", "700")
    time.sleep(2)

    print("[2] 加载模型（如果还没加载）")
    xml = dump_xml()
    if find(xml, "模型已就绪"):
        print("  已就绪，跳过")
    elif find(xml, "加载模型"):
        tap_text("加载模型")
        if not wait_text("模型已就绪", timeout=90):
            print("  加载未完成，退出")
            return 1
        print("  已就绪")
    else:
        print("  找不到加载入口，当前界面：")
        for kw in ("下载模型", "极限模式", "探测中"):
            if find(xml, kw):
                print(f"    看到「{kw}」")
        return 1

    print("[3] 切到跑分页")
    if not tap_text("跑分"):
        return 1
    time.sleep(2)

    print("[4] 开始跑分")
    if not tap_text("开始跑分"):
        xml = dump_xml()
        if find(xml, "复制 Markdown"):
            print("  已有跑分结果，直接采集")
        else:
            return 1

    print("[5] 等待跑分完成（最多 6 分钟）")
    ok = wait_text("复制", timeout=360)
    print("  完成" if ok else "  超时")

    xml = dump_xml()
    out = []
    for node in re.findall(r"<node[^>]*>", xml):
        t = re.search(r'text="([^"]*)"', node)
        if t and t.group(1).strip():
            out.append(t.group(1).replace("&#10;", " "))

    report = ROOT / ".tmp" / "benchmark_result.txt"
    report.parent.mkdir(exist_ok=True)
    report.write_text("\n".join(out), encoding="utf-8")
    print(f"[6] 界面文本已存到 {report}")

    png = subprocess.run(
        [ADB, "exec-out", "screencap", "-p"], capture_output=True, timeout=60
    ).stdout
    (ROOT / ".tmp" / "bench_final.png").write_bytes(png)
    return 0


if __name__ == "__main__":
    sys.exit(main())
