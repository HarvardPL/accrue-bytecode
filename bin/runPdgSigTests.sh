# Run through all the classes in the test.pdg.sig package and generate a PDG for each. Correctness to be determined by hand.
cd test/test/pdg/sig

for class in *.java
do
    cd ../../../../
    name=test.pdg.sig.${class%.*}
    echo "CHECKING" "$name"
    if [[ $name == *Case* ]] 
        then
            time bin/runAnalysis.sh -cp classes/test/pdg -e $name -n pdg -ea -writeDotPDG -fileLevel 1
        else
            time bin/runAnalysis.sh -cp classes/test/pdg -e $name -n pdg -ea -writeDotPDG
    fi
    cd test/test/pdg/sig
done