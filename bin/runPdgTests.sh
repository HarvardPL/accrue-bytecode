# Run through all the classes in the test.pdg package and generate a PDG for each. Correctness to be determined by hand.
cd test/test/pdg

for class in *.java
do
    cd ../../../
    name=test.pdg.${class%.*}
    echo "CHECKING" "$name"
    time bin/runAnalysis.sh -cp classes/test/pdg -e $name -n pdg -ea -writeDotPDG
    cd test/test/pdg
done