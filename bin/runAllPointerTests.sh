#!/bin/sh

if [ -z ${ACCRUE_BYTECODE+dummy} ]
then
    >&2 echo "Environment variable ACCRUE_BYTECODE is unset: \
scripts may fail if not run from top-level Accrue directory." 
    export ACCRUE_BYTECODE=$PWD
fi

cd $ACCRUE_BYTECODE/src/test/java/test/pointer

for filename in *.java
do
	class=${filename%.*}
	class="test.pointer."$class
	echo $class
	cd $ACCRUE_BYTECODE
	bin/runAnalysis.sh -e $class -n pointsto -ea
done	
