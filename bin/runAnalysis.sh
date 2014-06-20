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
classpath="$classpath:$dir/data"
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
# SCanDroid
classpath="$classpath:$WALA_HOME/../SCandroid/bin/"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/activation-1.1.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/commons-cli-1.2.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/commons-compiler-2.6.1.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/commons-io-2.4.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/commons-lang3-3.1.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/dexlib-1.3.4-dev.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/geronimo-jms_1.1_spec-1.0.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/groovy-all-2.0.0.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/guava-13.0.1.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/hamcrest-core-1.3.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/janino-2.6.1.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/jansi-1.8.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/jgrapht-0.8.3.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/jsr305-1.3.9.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/junit-4.11.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/logback-classic-1.0.9.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/logback-core-1.0.9.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/mail-1.4.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/servlet-api-2.5.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/lib/slf4j-api-1.7.2.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/wala/wala_cast.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/wala/wala_cast_java.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/wala/wala_cast_java_jdt.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/wala/wala_core_tests.jar"
classpath="$classpath:$WALA_HOME/../SCandroid/wala/wala_ide.jar"

run "$args"


