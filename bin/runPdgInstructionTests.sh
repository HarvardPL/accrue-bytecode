# Run through all the classes in the test.instruction package and generate a PDG for each. Correctness to be determined by hand.
cd src/test/instruction

for class in *.java
do
    cd ../../../
    name=test.instruction.${class%.*}
    echo "CHECKING" "$name"
    time bin/runAnalysis.sh -cp classes/test/instruction -e $name -n pdg -ea -offline -writeDotPDG
    cd src/test/instruction
done