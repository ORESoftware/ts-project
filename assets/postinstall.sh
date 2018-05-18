#!/usr/bin/env bash


export gmx_skip_postinstall="yes";

if [ -z "$(which gmx)" ]; then
    npm install -g gmx@latest
fi