#!/usr/bin/env bash
# 把 bootstrap zip 里残留的旧前缀改掉（只改文本脚本，不碰 ELF）。
# 用法: normalize-bootstrap-prefix.sh <bootstrap-aarch64.zip>  （原地改写）
#
# 背景: termux-packages 在 debscripts / post-massage 阶段生成的 shell 脚本
# （pkg、login、motd.sh、termux-tools.preinst 等）只被改了 shebang，
# 正文里的 /data/data/com.termux 没被替换，于是 pkg 一跑就 source 不到文件。
# ELF 一律不动：编译期已经带新前缀，若 ELF 里还有旧前缀说明前缀注入失败，必须报错。
set -euo pipefail

ZIP="${1:?usage: $0 <bootstrap-aarch64.zip>}"
OLD="/data/data/com.termux"
NEW="/data/data/com.aifactory.app"
ZIP_ABS="$(cd "$(dirname "$ZIP")" && pwd)/$(basename "$ZIP")"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -q "$ZIP_ABS" -d "$WORK/t"

cd "$WORK/t"
mapfile -t HITS < <(grep -rla -- "$OLD" . || true)
echo "leftover files carrying $OLD: ${#HITS[@]}"

ELF_HITS=0
TXT=0
for f in "${HITS[@]}"; do
	if file -b "$f" | grep -q ELF; then
		echo "::warning::ELF still carries old prefix: $f"
		ELF_HITS=$((ELF_HITS + 1))
		continue
	fi
	sed -i "s|$OLD|$NEW|g" "$f"
	TXT=$((TXT + 1))
	[ -n "${GITHUB_ACTIONS:-}" ] && echo "  rewrote $f" || true
done
echo "rewritten text files: $TXT, elf skipped: $ELF_HITS"

LEFT="$(grep -rla -- "$OLD" . || true)"
if [ -n "$LEFT" ]; then
	echo "::error::$OLD still present after rewrite:"
	echo "$LEFT"
	exit 1
fi

# 旧前缀清干净后，确认新前缀确实在（防止 sed 啥也没匹配还报成功）
grep -rla "$NEW" . >/dev/null || { echo "::error::$NEW not found at all"; exit 1; }

# 软件源：官方 Termux 源的包前缀是 /data/data/com.termux，装进本 runtime 会得到
# 错路径的二进制。默认停用它，只留 AIFactory 自己的源。
REPO_URL="${RUNTIME_REPO_URL:-}"
if [ -n "$REPO_URL" ]; then
	SL="etc/apt/sources.list"
	if [ -f "$SL" ]; then
		sed -i 's|^\(deb[[:space:]].*\)$|# \1|' "$SL"
	else
		mkdir -p "$(dirname "$SL")"
		: > "$SL"
	fi
	EXTRA_URL="${RUNTIME_EXTRA_URL:-}"
	{
		echo "# AIFactory runtime apt source"
		echo "# 官方 Termux 源已故意停用：其包内前缀是 Termux 自己的目录"
		echo "deb [trusted=yes] $REPO_URL ./"
		echo "# 补充源（Gradle 8.7 等），主流水线重建不会覆盖它"
		[ -n "${RUNTIME_EXTRA_URL:-}" ] && echo "deb [trusted=yes] $RUNTIME_EXTRA_URL ./"
		echo "# 主流水线之外的补充包（Gradle 8.7 等），重建 runtime-repo 不会覆盖它"
		[ -n "$EXTRA_URL" ] && echo "deb [trusted=yes] $EXTRA_URL ./"
		cat "$SL"
	} > "$SL.new"
	mv "$SL.new" "$SL"
	echo "sources.list -> $REPO_URL"
fi

cd "$WORK"
( cd "$WORK/t" && zip -q -r -X "$WORK/out.zip" . )
unzip -tq "$WORK/out.zip" >/dev/null
mv "$WORK/out.zip" "$ZIP_ABS"
echo "normalized: $ZIP_ABS"
ls -l "$ZIP_ABS"
sha256sum "$ZIP_ABS"
