#!/usr/bin/env bash


project_root="";

if [[ "$(uname -s)" == "Darwin" ]]; then
    project_root="$(dirname $(dirname $("$HOME/.oresoftware/bin/realpath" $0)))";

else
    project_root="$(dirname $(dirname $(realpath $0)))";
fi

npm_local_bin="${project_root}/node_modules/.bin";


js="$project_root/dist/cli.js"


### run this biatch
node "$js" "$@"
