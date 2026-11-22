#!/usr/bin/env bash
# 手工增删 runtime-repo 里的 deb 后，必须重新生成 Packages + Release。
# 只改 Packages 不改 Release，apt 会直接拒收：
#   File has unexpected size (40057 != 40531). Mirror sync in progress?
# 用法：bash regen-repo-index.sh <包含 *.deb 的目录>
set -euo pipefail
DIR="${1:?用法: regen-repo-index.sh <deb 目录>}"
cd "$DIR"

command -v dpkg-scanpackages >/dev/null || { echo "缺 dpkg-scanpackages（apt install dpkg-dev）"; exit 1; }

echo "扫描 deb …"
dpkg-scanpackages -m . /dev/null > Packages
sed -i 's|^Filename: \./|Filename: ./|' Packages
gzip -9 -c Packages > Packages.gz

# Release：apt 会按这里登记的体积+哈希校验，四项算法都得写全
{
  echo "Date: $(date -u '+%a, %d %b %Y %H:%M:%S +0000')"
  for pair in "MD5Sum:md5" "SHA1:sha1" "SHA256:sha256" "SHA512:sha512"; do
    echo "${pair%%:*}:"
    for f in Packages Packages.gz; do
      printf ' %s %16s %s\n' "$(${pair#*:}sum "$f" | cut -d' ' -f1)" "$(stat -c%s "$f")" "$f"
    done
  done
} > Release

echo "deb 数量: $(grep -c '^Package:' Packages)"
echo "Packages     $(stat -c%s Packages) B"
echo "Packages.gz  $(stat -c%s Packages.gz) B"
echo "Release 已按上面两个文件的体积与哈希重写 —— 三个文件必须一起上传（Release 最后传，或先传索引再传 Release）"
