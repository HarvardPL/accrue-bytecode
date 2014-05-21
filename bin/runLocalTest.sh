#!/bin/sh

vmargs="-Xmx16G -Xms13G"
dir=`dirname "$0"`/..

classpath="$dir/classes"

run() {
    if [ "$assert" = 1 ]; then
        cmmd="-classpath '$classpath' -ea test.integration.TestMain"
    else
        cmmd="-classpath '$classpath' test.integration.TestMain"
    fi

    if [ "$verbose" = 1 ]; then
        echo java "$vmargs" "$cmmd" "$args"
    fi
    
    eval java "$vmargs" "$cmmd" "$args"
}

clean() {
    for file in tests/*.dot
    do
	rm "$file"
    done
    cleaned=true
    echo removed files from tests directory
}

while true; do
  case "$1" in
    "")
      break
      ;;
    clean)
      clean
      shift
      ;;
    -ea)
      assert=1
      shift
      ;;
    -V)
      verbose=1
      shift
      ;;
    *)
      args="$args '$1'"
      shift
      ;;
  esac
done

classpath="$dir/classes"
classpath="$classpath:$WALA_CORE/bin"
classpath="$classpath:$dir/lib/wala-util.jar"
classpath="$classpath:$dir/lib/wala-shrike.jar"

run "$args"


