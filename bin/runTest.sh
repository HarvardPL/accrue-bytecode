#!/bin/sh

vmargs="-Xmx16G -Xms13G"
dir=`dirname "$0"`/..

classpath="$dir/classes"

run() {
    cmmd="-classpath '$classpath' -ea test.integration.TestMain"
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
classpath="$classpath:$dir/lib/wala-core.jar"
classpath="$classpath:$dir/lib/wala-util.jar"
classpath="$classpath:$dir/lib/wala-shrike.jar"

if [[ -n "$args" || -z cleaned ]]
then
    run "$args"
else
    echo removed files from tests directory
fi


