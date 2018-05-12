#!/usr/bin/env bash

set -e;

npm link;
cd "$HOME";
rm -rf "$HOME/xxx"
ts_project xxx/yyy/zafooxxuuzz --yes
