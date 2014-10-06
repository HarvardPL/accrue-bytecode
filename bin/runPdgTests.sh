# Run through all the classes in the test.instruction package and generate a PDG for each. Correctness to be determined by hand.
cd src/test/pdg

for class in *.java
do
    cd ../../../
    name=test.pdg.${class%.*}
    echo "CHECKING" "$name"
    time bin/runAnalysis.sh -cp classes/test/pdg -e $name -n pdg -ea -offline -writeDotPDG
    cd src/test/pdg
done