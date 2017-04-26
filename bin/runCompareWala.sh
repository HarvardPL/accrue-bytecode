# bin/runCompareWala.sh 2>&1 | tee wala$(date +"%Y.%m.%d.%H.%M.%S").txt  
# nohup bin/runCompareWala.sh > wala$(date +"%Y.%m.%d.%H.%M.%S").txt 2> walaerr$(date +"%Y.%m.%d.%H.%M.%S").txt &                                                                                                                                                                           
if [ -z ${ACCRUE_BYTECODE+dummy} ]
then
    >&2 echo "Environment variable ACCRUE_BYTECODE is unset: \
scripts may fail if not run from top-level Accrue directory." 
    export ACCRUE_BYTECODE=$PWD
fi

cd $ACCRUE_BYTECODE

max=10

for BM in \
  antlr bloat chart eclipse fop hsqldb jython luindex lusearch pmd xalan                                                                                                                                                           
do \

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo PROCESSING ${BM}...

for threads in 1 2 4 8 16 32; do 
echo $threads" threads"

for i in $(eval echo {1..$max}); do 

time bin/runAnalysis.sh -cp data/dacapo-2006-10-MR2.jar -e dacapo.${BM}.Main -n pointsto2 -disableSignatures -disableDefaultNativeSignatures -haf "WalaReceiverTypeContextSelector" -numThreads $threads -useSingleAllocForGenEx -testMode $1

done # run several times
done # run for many different thread numbers

echo FINISHED processing ${BM}
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo ""
echo ""

done # loop over all benchmarks
