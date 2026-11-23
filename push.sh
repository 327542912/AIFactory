#!/bin/sh
# 用法：GH_TOKEN=新的PAT sh push.sh
# 需要 PAT 作用域：repo（只推这一个仓库，别用 admin/delete 权限）
set -e
: "${GH_TOKEN:?请这样用：GH_TOKEN=你的PAT sh push.sh}"
cd "$(dirname "$0")"
git remote set-url origin "https://github.com/327542912/AIFactory.git"
git -c http.version=HTTP/1.1 -c http.postBuffer=52428800 \
    -c http.https://github.com/.extraheader="Authorization: basic $(printf 'x-access-token:%s' "$GH_TOKEN" | base64 | tr -d '\n')" \
    push -u origin HEAD:app-0.2.2 --tags
# CI 只在 app-0.2.2 上触发；main 同步源码与首页介绍
git -c http.version=HTTP/1.1 -c http.postBuffer=52428800 \
    -c http.https://github.com/.extraheader="Authorization: basic $(printf 'x-access-token:%s' "$GH_TOKEN" | base64 | tr -d '\n')" \
    push origin HEAD:main
echo "推送完成：https://github.com/327542912/AIFactory/commits/main"
