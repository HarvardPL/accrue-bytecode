cd src/test/instruction

for class in *.java
do
    cd ~/wala
    name=test.instruction.${class%.*}
    echo "CHECKING" "$name"
    time infoflow5c.sh src/test/instruction/$class -start $name -heapabstraction "[type(2,1) | scs(2) ]"
done    