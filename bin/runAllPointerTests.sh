#!/bin/sh

if [ -z ${ACCRUE_BYTECODE+dummy} ]
then
    >&2 echo "Environment variable ACCRUE_BYTECODE is unset: \
tests may fail if not run from Accrue base directory." 
else
    cd $ACCRUE_BYTECODE/src/test/java/test/pointer
fi

for filename in *.java
do
	class=${filename%.*}
	class="test.pointer."$class
	echo $class
	cd $ACCRUE_BYTECODE
	bin/runAnalysis.sh -e $class -n pointsto -ea
done	
