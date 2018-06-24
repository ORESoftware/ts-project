#!/usr/bin/env bash

get_latest_ts_project(){
  . "$HOME/.oresoftware/bash/"
}

tsproject(){
  ts_project $@
}

ts-project(){
  ts_project $@
}


ts_project(){
    if ! type -f ts_project &> /dev/null || ! which ts_project &> /dev/null; then
         npm i -g -s '@oresoftware/ts-project' || {
           return 1;
         }
    fi

    command ts_project $@;
}



export -f ts_project;
export -f ts-project;
export -f tsproject;
export -f get_latest_ts_project;

