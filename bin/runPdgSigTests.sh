# Run through all the classes in the test.pdg.sig package and generate a PDG for each. Correctness to be determined by hand.

if [ -z ${ACCRUE_BYTECODE+dummy} ]
then
    >&2 echo "Environment variable ACCRUE_BYTECODE is unset: \
tests may fail if not run from Accrue base directory." 
else
    cd $ACCRUE_BYTECODE/src/test/java/test/pdg/sig
fi

for class in *.java
do
    name=test.pdg.sig.${class%.*}
    echo "CHECKING" "$name"
    cd $ACCRUE_BYTECODE
    if [[ $name == *Case* ]] 
        then
            time bin/runAnalysis.sh -e $name -n pdg -ea -writeDotPDG -fileLevel 1
        else
            time bin/runAnalysis.sh -e $name -n pdg -ea -writeDotPDG
    fi
done
