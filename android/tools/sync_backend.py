#!/usr/bin/env python3
"""把后端的 glpmate/ 同步进 Android 的 APK 资源。

为什么必须有这个脚本：APK 里的 Python 代码是 `glpmate/` 的**副本**。
改了后端不同步，设备上就会一直跑旧代码——而且不会有任何报错，
APK 装得上、界面能打开，只是行为跟改动前一模一样。这个坑我们踩过两次。

用法（在 android/ 目录下）：
    python3 tools/sync_backend.py          # 同步 + 更新戳记
    python3 tools/sync_backend.py --check  # 只检查是否一致（给 CI 用）

构建前请务必跑一次。`--check` 在 CI 里可以在不一致时让构建失败。
"""
import argparse
import filecmp
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
# 后端源码在这个仓库的根目录（android/ 的上一级）
SRC = ROOT.parent / "glpmate"
ASSETS = ROOT / "app/src/main/assets/pyapp/code/glpmate"
PY_SRC = ROOT / "app/src/main/python"


def py_files(base: pathlib.Path) -> list[pathlib.Path]:
    return sorted(p for p in base.rglob("*.py") if "__pycache__" not in p.parts)


def sync(dst: pathlib.Path, check: bool) -> list[str]:
    changed: list[str] = []
    for src in py_files(SRC):
        rel = src.relative_to(SRC)
        target = dst / rel
        if target.is_file() and filecmp.cmp(src, target, shallow=False):
            continue
        changed.append(rel.as_posix())
        if not check:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, target)
    return changed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只检查不一致，不写入")
    args = ap.parse_args()

    if not SRC.is_dir():
        sys.exit(f"找不到后端源码：{SRC}（android/ 应该在仓库根目录下）")

    stamped = False
    for dst in (ASSETS, PY_SRC):
        if not dst.parent.is_dir():
            continue
        changed = sync(dst, args.check)
        label = dst.relative_to(ROOT).as_posix()
        if changed:
            verb = "不一致" if args.check else "已同步"
            print(f"{verb} {len(changed)} 个文件 → {label}")
            for name in changed:
                print(f"  {name}")
            stamped = True
        else:
            print(f"已是最新：{label}")

    # 戳记：代码变了才需要更新（gen_stamp 自己会判断是否最新）
    gen = ROOT / "tools/gen_stamp.py"
    if gen.is_file():
        cmd = [sys.executable, str(gen)] + (["--check"] if args.check else [])
        if subprocess.call(cmd) != 0:
            # --check 下 gen_stamp 返回 1 = 戳记过期，同样算不一致
            stamped = True

    # 关键：--check 发现任何不一致都必须以非 0 退出，否则 CI 里这个检查等于没有。
    # （最初写成在 if 分支里 return 1，结果检测到了却返回 0，静默失效。）
    if args.check and stamped:
        print("\n有未同步的改动。请在 android/ 下运行： python3 tools/sync_backend.py")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
