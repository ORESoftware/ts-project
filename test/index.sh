#!/usr/bin/env bash

set -e;

npm link -f;
cd "$HOME";
rm -rf "$HOME/xxxxdc"
ts_project xxxxdc/yyy/zafooxxuuzz --yes
