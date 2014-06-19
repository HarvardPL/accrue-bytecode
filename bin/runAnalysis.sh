#!/bin/sh

vmargs="-Xmx16G -Xms16G"
dir=`dirname "$0"`/..

if [ -z "$WALA_HOME" ]; then
  WALA_HOME=$dir/../WALA
fi
if [ ! -d "$WALA_HOME" ]; then
  echo "WALA not found: try defining the WALA_HOME environment variable to the directory containing all the WALA projects."
fi

run() {
    if [ "$assert" = 1 ]; then
        cmmd="-classpath '$classpath' -ea main.AccrueAnalysisMain"
    else
        cmmd="-classpath '$classpath' main.AccrueAnalysisMain"
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
    echo removed *.dot files from tests directory
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
classpath="$classpath:$dir/lib/JSON-java.jar"
classpath="$classpath:$dir/lib/jcommander-1.35.jar"
classpath="$classpath:$dir/lib/JFlex.jar"
classpath="$classpath:$dir/lib/java-cup-11a.jar"
classpath="$classpath:$WALA_HOME/com.ibm.wala.core/bin/"
classpath="$classpath:$WALA_HOME/com.ibm.wala.util/bin/"
classpath="$classpath:$WALA_HOME/com.ibm.wala.shrike/bin/"
classpath="$classpath:$WALA_HOME/com.ibm.wala.core/classes/"
classpath="$classpath:$WALA_HOME/com.ibm.wala.util/classes/"
classpath="$classpath:$WALA_HOME/com.ibm.wala.shrike/classes/"

run "$args"


