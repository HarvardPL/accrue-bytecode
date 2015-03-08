# bin/runAllAWS.sh 2>&1 | tee wala$(date +"%Y.%m.%d.%H.%M.%S").txt                                                                                                                                                                          
#
# run in disconnected shell
# nohup time bin/runAllAWS.sh &> wala$(date +"%Y.%m.%d.%H.%M.%S").txt &

for BM in \
  antlr bloat eclipse fop hsqldb luindex lusearch pmd xalan #chart jython                                                                                                                                                                   
do \

echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo PROCESSING ${BM}...

time bin/runAnalysis.sh -cp data/dacapo-2006-10-MR2.jar -e dacapo.${BM}.Main -n nonnull -useSingleAllocForImmutableWrappers -useSingleAllocForPrimitiveArrays -useSingleAllocPerThrowableType

echo FINISHED processing ${BM}
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo ""
echo ""

done