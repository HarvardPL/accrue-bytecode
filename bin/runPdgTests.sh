# Run through all the classes in the test.pdg package and generate a PDG for each. Correctness to be determined by hand.

if [ -z ${ACCRUE_BYTECODE} ]
then
    >&2 echo "Environment variable ACCRUE_BYTECODE is unset: \
scripts may fail if not run from top-level Accrue directory." 
    export ACCRUE_BYTECODE=$PWD
fi

cd $ACCRUE_BYTECODE/src/test/java/test/pdg

for class in *.java
do
    name=test.pdg.${class%.*}
    echo "CHECKING" "$name"
    cd $ACCRUE_BYTECODE 
    time bin/runAnalysis.sh -e $name -n pdg -ea -writeDotPDG
done
