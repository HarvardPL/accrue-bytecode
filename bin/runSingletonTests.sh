# bin/runSingletonTests.sh 2>&1 | tee wala$(date +"%Y.%m.%d.%H.%M.%S").txt  
# nohup bin/runSingletonTests.sh > wala$(date +"%Y.%m.%d.%H.%M.%S").txt 2> walaerr$(date +"%Y.%m.%d.%H.%M.%S").txt &                                                                                                                                                                           
if [ -z ${ACCRUE_BYTECODE+dummy} ]
then
    >&2 echo "Environment variable ACCRUE_BYTECODE is unset: \
scripts may fail if not run from top-level Accrue directory." 
    export ACCRUE_BYTECODE=$PWD
fi

cd $ACCRUE_BYTECODE

max=10
numThreads=16

    # -useSingleAllocForGenEx
    # -useSingleAllocForImmutableWrappers
    # -useSingleAllocForPrimitiveArrays
    # -useSingleAllocForStrings
    # -useSingleAllocForSwing
    # -useSingleAllocPerThrowableType


echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

echo "NO FLAGS"
for BM in \
  antlr bloat chart eclipse fop hsqldb disabled_jython luindex lusearch pmd xalan                                                                                                                                                           
do \

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo PROCESSING ${BM}...

for threads in $numThreads; do 
echo $threads" threads"

for i in $(eval echo {1..$max}); do 

time bin/runAnalysis.sh -cp data/dacapo-2006-10-MR2.jar -e dacapo.${BM}.Main -n pointsto2 -numThreads $threads -testMode -useSingleAllocForGenEx

done # run several times
for i in $(eval echo {$max..10}); do 
echo ""
done
done # run for many different thread numbers



echo FINISHED processing ${BM}
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo ""
echo ""

done # loop over all benchmarks

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

echo "PRIMITIVE ARRAYS"
for BM in \
  antlr bloat chart eclipse fop hsqldb disabled_jython luindex lusearch pmd xalan                                                                                                                                                           
do \

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo PROCESSING ${BM}...

for threads in $numThreads; do 
echo $threads" threads"

for i in $(eval echo {1..$max}); do 

time bin/runAnalysis.sh -cp data/dacapo-2006-10-MR2.jar -e dacapo.${BM}.Main -n pointsto2 -numThreads $threads -testMode -useSingleAllocForGenEx -useSingleAllocForPrimitiveArrays

done # run several times
for i in $(eval echo {$max..10}); do 
echo ""
done
done # run for many different thread numbers



echo FINISHED processing ${BM}
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo ""
echo ""

done # loop over all benchmarks

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

echo "STRINGS"
for BM in \
  antlr bloat chart eclipse fop hsqldb disabled_jython luindex lusearch pmd xalan                                                                                                                                                           
do \

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo PROCESSING ${BM}...

for threads in $numThreads; do 
echo $threads" threads"

for i in $(eval echo {1..$max}); do 

time bin/runAnalysis.sh -cp data/dacapo-2006-10-MR2.jar -e dacapo.${BM}.Main -n pointsto2 -numThreads $threads -testMode -useSingleAllocForGenEx -useSingleAllocForStrings

done # run several times
for i in $(eval echo {$max..10}); do 
echo ""
done
done # run for many different thread numbers



echo FINISHED processing ${BM}
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo ""
echo ""

done # loop over all benchmarks

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

echo "IMMUTABLE WRAPPERS"
for BM in \
  antlr bloat chart eclipse fop hsqldb disabled_jython luindex lusearch pmd xalan                                                                                                                                                           
do \

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo PROCESSING ${BM}...

for threads in $numThreads; do 
echo $threads" threads"

for i in $(eval echo {1..$max}); do 

time bin/runAnalysis.sh -cp data/dacapo-2006-10-MR2.jar -e dacapo.${BM}.Main -n pointsto2 -numThreads $threads -testMode -useSingleAllocForGenEx -useSingleAllocForImmutableWrappers

done # run several times
for i in $(eval echo {$max..10}); do 
echo ""
done
done # run for many different thread numbers



echo FINISHED processing ${BM}
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo ""
echo ""

done # loop over all benchmarks

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

echo "THROWABLE"
for BM in \
  antlr bloat chart eclipse fop hsqldb disabled_jython luindex lusearch pmd xalan                                                                                                                                                           
do \

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo PROCESSING ${BM}...

for threads in $numThreads; do 
echo $threads" threads"

for i in $(eval echo {1..$max}); do 

time bin/runAnalysis.sh -cp data/dacapo-2006-10-MR2.jar -e dacapo.${BM}.Main -n pointsto2 -numThreads $threads -testMode -useSingleAllocPerThrowableType

done # run several times
for i in $(eval echo {$max..10}); do 
echo ""
done
done # run for many different thread numbers



echo FINISHED processing ${BM}
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo ""
echo ""

done # loop over all benchmarks

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

echo "ALL FLAGS"
for BM in \
  antlr bloat chart eclipse fop hsqldb disabled_jython luindex lusearch pmd xalan                                                                                                                                                           
do \

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo PROCESSING ${BM}...

for threads in $numThreads; do 
echo $threads" threads"

for i in $(eval echo {1..$max}); do 

time bin/runAnalysis.sh -cp data/dacapo-2006-10-MR2.jar -e dacapo.${BM}.Main -n pointsto2 -numThreads $threads -testMode -useSingleAllocForPrimitiveArrays -useSingleAllocPerThrowableType -useSingleAllocForImmutableWrappers

done # run several times
for i in $(eval echo {$max..10}); do 
echo ""
done
done # run for many different thread numbers



echo FINISHED processing ${BM}
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo ""
echo ""

done # loop over all benchmarks
