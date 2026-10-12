#!/data/data/com.termux/files/usr/bin/bash
# GLP Mate Lite 一键部署（在 Termux 里运行）
#
# 用法：
#   bash /sdcard/glpmate/setup.sh
#
# 做的事：
#   1. 检查 python3 / sqlite3 是否就绪（没装会提示你先装）
#   2. 把 /sdcard/glpmate/glpmate-lite.tar.gz 解到 ~/glpmate-lite
#   3. 申请存储权限（读导出文件、写数据库）
#   4. 生成启动脚本 glpmate-start / glpmate-stop
#   5. 启动服务并做健康检查
#
# 反复运行是安全的：已有数据不会被覆盖。

set -u

PKG="/sdcard/glpmate/glpmate-lite.tar.gz"
DEST="$HOME/glpmate-lite"
PORT=8848

say()  { printf '\033[36m%s\033[0m\n' "$*"; }
ok()   { printf '\033[32m✅ %s\033[0m\n' "$*"; }
warn() { printf '\033[33m⚠️  %s\033[0m\n' "$*"; }
die()  { printf '\033[31m❌ %s\033[0m\n' "$*" >&2; exit 1; }

say "==> 1/5 检查依赖"
command -v python3 >/dev/null 2>&1 || die "没找到 python3。请先在 Termux 里运行：pkg install -y python sqlite"
PYVER="$(python3 -V 2>&1)"
ok "python3 就绪：$PYVER"
if command -v sqlite3 >/dev/null 2>&1; then
  ok "sqlite3 就绪（命令行工具，可选）"
else
  warn "没有 sqlite3 命令行工具。后端用的是 Python 内置 sqlite3 模块，不影响运行。"
fi

say "==> 2/5 解包"
[ -f "$PKG" ] || die "找不到部署包 $PKG（请确认已从电脑推送）"
mkdir -p "$HOME"
if [ -d "$DEST" ]; then
  warn "已存在 $DEST，只更新代码，保留 data/ 里的数据库"
  tar -xzf "$PKG" -C "$HOME" --exclude='glpmate-lite/data' || die "解包失败"
else
  tar -xzf "$PKG" -C "$HOME" || die "解包失败"
fi
[ -f "$DEST/run.py" ] || die "解包后没找到 run.py，包可能损坏"
ok "代码就位：$DEST"

say "==> 3/5 存储权限"
if [ -d "$HOME/storage" ]; then
  ok "存储权限已申请过"
else
  warn "即将申请存储权限，请在弹出框里点「允许」"
  termux-setup-storage 2>/dev/null || warn "termux-setup-storage 不可用，跳过"
  sleep 2
fi

say "==> 4/5 生成启动脚本"
cat > "$HOME/glpmate-start" <<START
#!/data/data/com.termux/files/usr/bin/bash
# 启动 GLP Mate Lite（后台运行，日志写到 ~/glpmate.log）
cd "$DEST" || exit 1
if pgrep -f "run.py --host" >/dev/null 2>&1; then
  echo "服务已在运行：http://127.0.0.1:$PORT/"
  exit 0
fi
nohup python3 run.py --host 127.0.0.1 --port $PORT > "\$HOME/glpmate.log" 2>&1 &
sleep 2
if curl -s -m 5 "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1; then
  echo "已启动：http://127.0.0.1:$PORT/"
  echo "日志：tail -f ~/glpmate.log"
else
  echo "启动失败，看看日志：tail -30 ~/glpmate.log"
fi
START
cat > "$HOME/glpmate-stop" <<'STOP'
#!/data/data/com.termux/files/usr/bin/bash
pkill -f "run.py --host" && echo "已停止" || echo "没有在运行的服务"
STOP
cat > "$HOME/glpmate-import" <<IMPORT
#!/data/data/com.termux/files/usr/bin/bash
# 导入华为运动健康的 HiTrack 文件
# 用法: glpmate-import [目录]     默认 /sdcard/Download
DIR="\${1:-/sdcard/Download}"
cd "$DEST" || exit 1
python3 tools/import_hitrack.py "\$DIR" --db "$DEST/data/glpmate.db"
IMPORT
chmod +x "$HOME/glpmate-start" "$HOME/glpmate-stop" "$HOME/glpmate-import"
ok "已生成：glpmate-start / glpmate-stop / glpmate-import"

say "==> 5/5 启动并自检"
bash "$HOME/glpmate-start"

echo
ok "部署完成"
cat <<'TIP'

接下来：
  启动        ~/glpmate-start
  停止        ~/glpmate-stop
  看日志      tail -f ~/glpmate.log
  导入运动数据 ~/glpmate-import /sdcard/Download

浏览器打开 http://127.0.0.1:8848/ 即可看到界面。

华为的 HiTrack 文件在这个目录（需要 root 权限复制出来）：
  /data/data/com.huawei.health/files/HiTrack_*
可以先复制到共享目录再导入：
  su -c "cp /data/data/com.huawei.health/files/HiTrack_* /sdcard/Download/"
然后运行 ~/glpmate-import

⚠️ 说明：HiTrack 只包含你手动记录的运动（含运动期间心率），
   不含 24 小时连续心率与血氧。连续数据要等隐私中心的数据副本批下来。
TIP
