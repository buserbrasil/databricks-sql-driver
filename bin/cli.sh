#!/bin/bash
#cp ./bin/deps.edn ~/.clojure/deps.edn
cd ../metabase

function nrepl() {
    clojure -M:user/databricks-sql:nrepl
}

function repl() {
    clojure -M:user/databricks-sql
}

function options() {
    echo "Menu Options:"
    echo "[1] nrepl  - start an nREPL"
    echo "[2] repl   - start regular repl"
    echo "[3] exit   - exit the script"
}

# Main loop
while true; do
  options
  read -p "Enter your choice (1/2/3): " choice

  case $choice in
    1)
      nrepl
      ;;
    2)
      repl
      ;;
    *)
      echo "Exiting the script."
      cd /driver
      exit 0
      ;;
  esac
done