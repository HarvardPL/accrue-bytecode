# bin/runAllAWS.sh 2>&1 | tee wala$(date +"%Y.%m.%d.%H.%M.%S").txt                                                                                                                                                                          

max=1

for BM in \
  antlr bloat chart eclipse fop hsqldb jython luindex lusearch pmd xalan                                                                                                                                                           
do \

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo PROCESSING ${BM}...

for threads in 2 4 6 8 10 12 14 16 18 20 22 24 26 28 30 32; do 
echo $threads" threads"

for i in $(eval echo {1..$max}); do 

time bin/runAnalysis.sh -cp data/dacapo-2006-10-MR2.jar -e dacapo.${BM}.Main -n pointsto2 -disableSignatures -disableDefaultNativeSignatures -haf "WalaReceiverTypeContextSelector" -numThreads $threads -testMode $1

done # run several times
done # run for many different thread numbers

echo FINISHED processing ${BM}
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo ""
echo ""

done # loop over all benchmarks