#!/bin/sh

cd src/test/pointer/

for filename in *.java
do
	class=${filename%.*}
	class="test.pointer."$class
	echo $class
	cd /Users/mu/wala
	bin/runAnalysis.sh -e $class -n pointsto -ea
done	