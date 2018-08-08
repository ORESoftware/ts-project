#!/usr/bin/env bash

set -e;

if [[ "$ts_project_skip_postinstall" == "yes" ]]; then
  echo "skipping tsproject postinstall routine.";
  exit 0;
fi


export ts_project_skip_postinstall="yes";

if ! which gmx; then
    npm i -s -g gmx || {
       echo "Could not install 'gmx' during postinstall routine. No big deal.";
    }
fi


mkdir -p "$HOME/.oresoftware/bash" || {
  echo "Could not create oresoftware/bash dir."
  exit 1;
}


cat "assets/shell.sh" > "$HOME/.oresoftware/bash/ts_project.sh" || {
  echo "Could not create oresoftware/bash/ts_project.sh file."
  exit 1;
}


(

    shell_file="node_modules/@oresoftware/shell/assets/shell.sh";
    [ -f "$shell_file" ] && cat "$shell_file" > "$HOME/.oresoftware/shell.sh" && {
        echo "Successfully copied @oresoftware/shell/assets/shell.sh to $HOME/.oresoftware/shell.sh";
        exit 0;
    }

    shell_file="../shell/assets/shell.sh";
    [ -f "$shell_file" ] &&  cat "../shell/assets/shell.sh" > "$HOME/.oresoftware/shell.sh" && {
        echo "Successfully copied @oresoftware/shell/assets/shell.sh to $HOME/.oresoftware/shell.sh";
        exit 0;
    }

    curl -H 'Cache-Control: no-cache' \
         "https://raw.githubusercontent.com/oresoftware/shell/master/assets/shell.sh?$(date +%s)" \
          --output "$HOME/.oresoftware/shell.sh" 2> /dev/null || {
           echo "curl command failed to read shell.sh";
           exit 1;
    }
)



echo; echo -e "${r2g_green} => tsproject was installed successfully.${r2g_no_color}";
echo -e "Add the following line to your .bashrc/.bash_profile files:";
echo -e "${r2g_cyan} . \"\$HOME/.oresoftware/shell.sh\"${r2g_no_color}"; echo;
