#!/usr/bin/env bash

set -e;

npm link;
cd "$HOME";
rm -rf "$HOME/xxxxdc"
ts_project xxxxdc/yyy/zafooxxuuzz --yes
