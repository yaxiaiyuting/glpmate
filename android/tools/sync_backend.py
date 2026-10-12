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


def find_backend() -> pathlib.Path | None:
    """定位后端源码的 glpmate/ 目录。

    要适配两种布局，因为它们在开发时和发布后不一样：
      - 发布仓库：   <repo>/glpmate/            与 android/ 同级（android/ 是子目录）
      - 独立开发目录：<work>/glpmate-native/  与 glpmate-lite/glpmate/ 是兄弟目录
                       这时 android 的上一级就是 <work>/glpmate-lite

    写死其中一种都会让另一种布局下脚本静默失效（发布版第一次克隆下来就踩到了：
    报"有未同步的改动"，其实完全一致，因为它在错误的路径上找不到源码）。
    """
    candidates = [
        ROOT.parent / "glpmate",               # 发布仓库：android/ 的上一级
        ROOT.parent / "glpmate-lite" / "glpmate",
        ROOT.parent.parent / "glpmate",        # 兜底
    ]
    for c in candidates:
        if (c / "models.py").is_file():
            return c
    return None


SRC = find_backend()
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

    if SRC is None or not SRC.is_dir():
        sys.exit(
            "找不到后端源码 glpmate/。\n"
            "  已在这些位置找过：android/ 同级、android/../glpmate-lite/、上两级\n"
            "  请确认仓库结构完整（glpmate/ 应与 android/ 同级）"
        )
    print(f"后端源码：{SRC}")

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
